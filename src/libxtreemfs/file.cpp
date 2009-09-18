// Copyright 2009 Minor Gordon.
// This source comes from the XtreemFS project. It is licensed under the GPLv2 (see COPYING for terms and conditions).

#include "xtreemfs/file.h"
#include "xtreemfs/mrc_proxy.h"
#include "xtreemfs/volume.h"
using namespace xtreemfs;

#include "yieldfs.h"

#include <errno.h>

#ifdef _WIN32
#include <windows.h>
#pragma warning( push )
#pragma warning( disable: 4100 )
#else
#include <errno.h>
#endif


#define FILE_OPERATION_BEGIN \
  try \
  {

#define FILE_OPERATION_END \
  } \
  catch ( ProxyExceptionResponse& proxy_exception_response ) \
  { \
    YIELD::Exception::set_errno( proxy_exception_response.get_platform_error_code() ); \
    return false; \
  } \
  catch ( std::exception& ) \
  { \
    YIELD::Exception::set_errno( EIO ); \
    return false; \
  }


namespace xtreemfs
{
  class FileReadBuffer : public yidl::FixedBuffer
  {
  public:
    FileReadBuffer( void* buf, size_t len )
      : FixedBuffer( len )
    {
      iov.iov_base = buf;
    }
  };

  class FileReadResponse : public org::xtreemfs::interfaces::OSDInterface::readResponse
  {
  public:
    FileReadResponse( yidl::auto_Buffer buffer )
      : org::xtreemfs::interfaces::OSDInterface::readResponse( org::xtreemfs::interfaces::ObjectData( 0, false, 0, buffer ) )
    { }
  };

  class FileReadRequest : public org::xtreemfs::interfaces::OSDInterface::readRequest
  {
  public:
    FileReadRequest( const org::xtreemfs::interfaces::FileCredentials& file_credentials, const std::string& file_id, uint64_t object_number, uint64_t object_version, uint32_t offset, uint32_t length, yidl::auto_Buffer buffer ) 
      : org::xtreemfs::interfaces::OSDInterface::readRequest( file_credentials, file_id, object_number, object_version, offset, length ), 
      buffer( buffer )
    { }

    YIELD::auto_Response createResponse() { return new FileReadResponse( buffer ); }

  private:
    yidl::auto_Buffer buffer;
  };


  class FileWriteBuffer : public yidl::FixedBuffer
  {
  public:
    FileWriteBuffer( const void* buf, size_t len )
      : FixedBuffer( len )
    {
      iov.iov_base = const_cast<void*>( buf );
      iov.iov_len = len;
    }
  };
};


File::File( yidl::auto_Object<Volume> parent_volume, yidl::auto_Object<MRCProxy> mrc_proxy, const YIELD::Path& path, const org::xtreemfs::interfaces::FileCredentials& file_credentials )
: parent_volume( parent_volume ), mrc_proxy( mrc_proxy ), path( path ), file_credentials( file_credentials )
{
  selected_file_replica = 0;
}

File::~File()
{
  try
  {
    if ( !latest_osd_write_response.get_new_file_size().empty() )  
      mrc_proxy->xtreemfs_update_file_size( file_credentials.get_xcap(), latest_osd_write_response );

    if ( file_credentials.get_xcap().get_replicateOnClose() )
      parent_volume->get_mrc_proxy()->close( file_credentials.get_xcap() );
  }
  catch ( std::exception& )
  { }
}

bool File::datasync()
{
  return sync();
}

bool File::close()
{
  return true;
}

YIELD::auto_Stat File::getattr()
{
  return parent_volume->getattr( path );
}

bool File::getlk( bool exclusive, uint64_t offset, uint64_t length )
{
  org::xtreemfs::interfaces::Lock lock = parent_volume->get_osd_proxy_mux()->xtreemfs_lock_check( file_credentials, parent_volume->get_uuid(), yieldfs::FUSE::getpid(), file_credentials.get_xcap().get_file_id(), offset, length, exclusive );
  return lock.get_client_pid() != yieldfs::FUSE::getpid();
}

size_t File::getpagesize()
{
  return file_credentials.get_xlocs().get_replicas()[0].get_striping_policy().get_stripe_size() * 1024;
}

uint64_t File::get_size()
{
  if ( !latest_osd_write_response.get_new_file_size().empty() )  
    return latest_osd_write_response.get_new_file_size()[0].get_size_in_bytes();
  else
    return YIELD::File::get_size();
}

bool File::getxattr( const std::string& name, std::string& out_value )
{
  return parent_volume->getxattr( path, name, out_value );
}

bool File::listxattr( std::vector<std::string>& out_names )
{
  return parent_volume->listxattr( path, out_names );
}

ssize_t File::read( void* rbuf, size_t size, uint64_t offset )
{
  std::vector<org::xtreemfs::interfaces::OSDInterface::readResponse*> read_responses;
  YIELD::auto_Log log( parent_volume->get_log() );
  ssize_t ret = 0;

  try
  {
#ifdef _DEBUG
    if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
      log->getStream( YIELD::Log::LOG_INFO ) << "xtreemfs::File::read( rbuf, size=" << size << ", offset=" << offset << " )";
#endif

    char *rbuf_start = static_cast<char*>( rbuf ), *rbuf_p = static_cast<char*>( rbuf ), *rbuf_end = static_cast<char*>( rbuf ) + size;
    uint64_t current_file_offset = offset;
    uint32_t stripe_size = file_credentials.get_xlocs().get_replicas()[0].get_striping_policy().get_stripe_size() * 1024;    

    YIELD::auto_ResponseQueue<org::xtreemfs::interfaces::OSDInterface::readResponse> read_response_queue( new YIELD::ResponseQueue<org::xtreemfs::interfaces::OSDInterface::readResponse> );
    size_t expected_read_response_count = 0;

    while ( rbuf_p < rbuf_end )
    {      
      uint64_t object_number = current_file_offset / stripe_size;
      uint32_t object_offset = current_file_offset % stripe_size;
      size_t object_size = static_cast<size_t>( rbuf_end - rbuf_p );
      if ( object_offset + object_size > stripe_size )
        object_size = stripe_size - object_offset;

#ifdef _DEBUG
      if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
      {
        log->getStream( YIELD::Log::LOG_INFO ) << 
          "xtreemfs::File: issuing read # " << ( expected_read_response_count + 1 ) <<
          " for " << object_size << 
          " bytes from object number " << object_number <<
          " in file " << file_credentials.get_xcap().get_file_id() <<
          " (object offset = " << object_offset <<
          ", file offset = " << current_file_offset <<
          ", remaining buffer size = " << static_cast<size_t>( rbuf_end - rbuf_p ) <<
          ").";
      }
#endif

      org::xtreemfs::interfaces::OSDInterface::readRequest* read_request = new FileReadRequest( file_credentials, file_credentials.get_xcap().get_file_id(), object_number, 0, object_offset, static_cast<uint32_t>( object_size ), new FileReadBuffer( rbuf_p, object_size ) );
      read_request->set_response_target( read_response_queue->incRef() );
      read_request->set_selected_file_replica( selected_file_replica );

      parent_volume->get_osd_proxy_mux()->send( *read_request );
      expected_read_response_count++;

      rbuf_p += object_size;
      current_file_offset += object_size;
    }

#ifdef _DEBUG
    if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
      log->getStream( YIELD::Log::LOG_INFO ) << "xtreemfs::File: issued " << expected_read_response_count << " parallel reads.";
#endif

    for ( size_t read_response_i = 0; read_response_i < expected_read_response_count; read_response_i++ )
    {
      org::xtreemfs::interfaces::OSDInterface::readResponse& read_response = read_response_queue->dequeue();
      // Object::decRef( read_response );
      read_responses.push_back( &read_response );

      yidl::auto_Buffer data( read_response.get_object_data().get_data() );
      rbuf_p = static_cast<char*>( static_cast<void*>( *data ) );
      uint32_t zero_padding = read_response.get_object_data().get_zero_padding();
      if ( read_response.get_selected_file_replica() != 0 )
        selected_file_replica = read_response.get_selected_file_replica();

#ifdef _DEBUG
      if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
      {
        log->getStream( YIELD::Log::LOG_INFO ) << 
          "xtreemfs::File: read " << data->size() <<
          " bytes from file " << file_credentials.get_xcap().get_file_id() <<            
          " with " << zero_padding << " bytes of zero padding" <<
          ", starting from buffer offset " << static_cast<size_t>( rbuf_p - rbuf_start ) << 
          ", read # " << ( read_response_i + 1 ) << " of " << expected_read_response_count << " parallel reads" <<
          ".";
      }
#endif

      ret += data->size();

      if ( zero_padding > 0 )
      { 
        rbuf_p += data->size();

        if ( rbuf_p + zero_padding <= rbuf_end )
        {
          memset( rbuf_p, 0, zero_padding );
          ret += zero_padding;
        }
        else
        {
          log->getStream( YIELD::Log::LOG_ERR ) << "xtreemfs::File: received zero_padding (data size=" << data->size() << ", zero_padding=" << zero_padding << ") larger than available buffer space (" << static_cast<size_t>( rbuf_end - rbuf_p ) << ")";
          YIELD::ExceptionResponse::set_errno( EIO );
          ret = -1;
          break;
        }
      }
    }

#ifdef _DEBUG
    if ( static_cast<size_t>( ret ) > size ) 
      DebugBreak();

    if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
    {
      log->getStream( YIELD::Log::LOG_INFO ) << 
        "xtreemfs::File: read " << ret <<
        " bytes from file " << file_credentials.get_xcap().get_file_id() <<
        " in total, returning from read().";           
    }
#endif
  }
  catch ( ProxyExceptionResponse& proxy_exception_response )
  {
#ifdef _DEBUG
    if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
    {
      log->getStream( YIELD::Log::LOG_INFO ) <<
      "xtreemfs::File: read threw ProxyExceptionResponse " <<
      "(errno=" << proxy_exception_response.get_platform_error_code() <<
      ", strerror=" << YIELD::Exception::strerror( proxy_exception_response.get_platform_error_code() ) << ").";
    }
#endif

    YIELD::Exception::set_errno( proxy_exception_response.get_platform_error_code() );

    ret = -1;
  }
  catch ( std::exception& exc )
  {
#ifdef _DEBUG
    if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
    {
      log->getStream( YIELD::Log::LOG_INFO ) <<
      "xtreemfs::File: read threw std::exception " <<
      "(what=" << exc.what() << ")" <<
      ", setting errno to EIO.";
    }
#endif

    YIELD::Exception::set_errno( EIO );

    ret = -1;
  }

  for ( std::vector<org::xtreemfs::interfaces::OSDInterface::readResponse*>::iterator file_read_oncrpc_response_i = read_responses.begin(); file_read_oncrpc_response_i != read_responses.end(); file_read_oncrpc_response_i++ )
    yidl::Object::decRef( **file_read_oncrpc_response_i );

  return ret;
}

bool File::removexattr( const std::string& name )
{
  return parent_volume->removexattr( path, name );
}

bool File::setlk( bool exclusive, uint64_t offset, uint64_t length )
{
  FILE_OPERATION_BEGIN;

  org::xtreemfs::interfaces::Lock lock = parent_volume->get_osd_proxy_mux()->xtreemfs_lock_acquire( file_credentials, parent_volume->get_uuid(), yieldfs::FUSE::getpid(), file_credentials.get_xcap().get_file_id(), offset, length, exclusive );
  locks.push_back( lock );
  return true;

  FILE_OPERATION_END;
}

bool File::setlkw( bool exclusive, uint64_t offset, uint64_t length )
{
  FILE_OPERATION_BEGIN;

  for ( ;; )
  {
    try
    {
      org::xtreemfs::interfaces::Lock lock = parent_volume->get_osd_proxy_mux()->xtreemfs_lock_acquire( file_credentials, parent_volume->get_uuid(), yieldfs::FUSE::getpid(), file_credentials.get_xcap().get_file_id(), offset, length, exclusive );
      locks.push_back( lock );
      return true;
    }
    catch ( ProxyExceptionResponse& )
    { }
  }

  FILE_OPERATION_END;
}

bool File::setxattr( const std::string& name, const std::string& value, int flags )
{
  return parent_volume->setxattr( path, name, value, flags );
}

bool File::sync()
{
  FILE_OPERATION_BEGIN;

  if ( !latest_osd_write_response.get_new_file_size().empty() )  
  {
    mrc_proxy->xtreemfs_update_file_size( file_credentials.get_xcap(), latest_osd_write_response );
    latest_osd_write_response.set_new_file_size( org::xtreemfs::interfaces::NewFileSize() );
  }  

  return true;

  FILE_OPERATION_END;
}

bool File::truncate( uint64_t new_size )
{
  FILE_OPERATION_BEGIN;

  org::xtreemfs::interfaces::XCap truncate_xcap;
  mrc_proxy->ftruncate( file_credentials.get_xcap(), truncate_xcap );
  file_credentials.set_xcap( truncate_xcap );
  org::xtreemfs::interfaces::OSDWriteResponse osd_write_response;

  parent_volume->get_osd_proxy_mux()->truncate( file_credentials, file_credentials.get_xcap().get_file_id(), new_size, osd_write_response );

  if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_CACHE_METADATA ) != Volume::VOLUME_FLAG_CACHE_METADATA )
    mrc_proxy->xtreemfs_update_file_size( file_credentials.get_xcap(), osd_write_response );
  else if ( osd_write_response > latest_osd_write_response )
    latest_osd_write_response = osd_write_response;

  return true;

  FILE_OPERATION_END;
}

bool File::unlk( uint64_t offset, uint64_t length )
{
  FILE_OPERATION_BEGIN;

  if ( locks.empty() )
    return true;
  else
  {
    parent_volume->get_osd_proxy_mux()->xtreemfs_lock_release( file_credentials, file_credentials.get_xcap().get_file_id(), locks.back() );
    locks.pop_back();
    return true;
  }

  FILE_OPERATION_END;
}

ssize_t File::write( const void* wbuf, size_t size, uint64_t offset )
{
  YIELD::auto_Log log( parent_volume->get_log() );
  ssize_t ret;
  std::vector<org::xtreemfs::interfaces::OSDInterface::writeResponse*> write_responses;

#ifdef _DEBUG
  if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
    log->getStream( YIELD::Log::LOG_INFO ) << "xtreemfs::File::write( wbuf, size=" << size << ", offset=" << offset << " )";
#endif

  try
  {
    const char *wbuf_p = static_cast<const char*>( wbuf ), *wbuf_end = static_cast<const char*>( wbuf ) + size;
    uint64_t current_file_offset = offset;
    uint32_t stripe_size = file_credentials.get_xlocs().get_replicas()[0].get_striping_policy().get_stripe_size() * 1024;

    YIELD::auto_ResponseQueue<org::xtreemfs::interfaces::OSDInterface::writeResponse> write_response_queue( new YIELD::ResponseQueue<org::xtreemfs::interfaces::OSDInterface::writeResponse> );
    size_t expected_write_response_count = 0;

    while ( wbuf_p < wbuf_end )
    {
      uint64_t object_number = current_file_offset / stripe_size;
      uint32_t object_offset = current_file_offset % stripe_size;
      uint64_t object_size = static_cast<size_t>( wbuf_end - wbuf_p );
      if ( object_offset + object_size > stripe_size )
        object_size = stripe_size - object_offset;
      org::xtreemfs::interfaces::ObjectData object_data( 0, false, 0, new FileWriteBuffer( wbuf_p, static_cast<uint32_t>( object_size ) ) );
      org::xtreemfs::interfaces::OSDInterface::writeRequest* write_request = new org::xtreemfs::interfaces::OSDInterface::writeRequest( file_credentials, file_credentials.get_xcap().get_file_id(), object_number, 0, object_offset, 0, object_data );
      write_request->set_selected_file_replica( selected_file_replica );

#ifdef _DEBUG
      if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
      {
        log->getStream( YIELD::Log::LOG_INFO ) << 
          "xtreemfs::File: issuing write # " << ( expected_write_response_count + 1 ) <<
          " of " << object_size << 
          " bytes to object number " << object_number <<
          " in file " << file_credentials.get_xcap().get_file_id() <<
          " (object offset = " << object_offset <<
          ", file offset = " << current_file_offset  <<
          ").";
      }
#endif

      write_request->set_response_target( write_response_queue->incRef() );
      parent_volume->get_osd_proxy_mux()->send( *write_request );
      expected_write_response_count++;

      wbuf_p += object_size;
      current_file_offset += object_size;
    }

    for ( size_t write_response_i = 0; write_response_i < expected_write_response_count; write_response_i++ )
    {
      org::xtreemfs::interfaces::OSDInterface::writeResponse& write_response = write_response_queue->dequeue();
      if ( write_response.get_selected_file_replica() != 0 )
        selected_file_replica = write_response.get_selected_file_replica();

#ifdef _DEBUG
      if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
        log->getStream( YIELD::Log::LOG_INFO ) << "xtreemfs::File: write received response # " << ( write_response_i + 1 ) << " of " << expected_write_response_count << ".";
#endif

      if ( write_response.get_osd_write_response() > latest_osd_write_response )
      {
#ifdef _DEBUG
        if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
          log->getStream( YIELD::Log::LOG_INFO ) << "xtreemfs::File: OSD write response is newer than latest known.";
#endif

        latest_osd_write_response = write_response.get_osd_write_response();
      }

      // yidl::Object::decRef( write_response );
      write_responses.push_back( &write_response );
    }

    if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_CACHE_METADATA ) != Volume::VOLUME_FLAG_CACHE_METADATA )
    {
#ifdef _DEBUG
      if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
        log->getStream( YIELD::Log::LOG_INFO ) << "xtreemfs::File: flushing file size updates.";
#endif

      mrc_proxy->xtreemfs_update_file_size( file_credentials.get_xcap(), latest_osd_write_response );
      latest_osd_write_response.set_new_file_size( org::xtreemfs::interfaces::NewFileSize() );
    }

    ret = static_cast<ssize_t>( current_file_offset - offset );
  }
  catch ( ProxyExceptionResponse& proxy_exception_response )
  {
#ifdef _DEBUG
    if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
    {
      log->getStream( YIELD::Log::LOG_INFO ) <<
      "xtreemfs::File: write threw ProxyExceptionResponse " <<
      "(errno= " << proxy_exception_response.get_platform_error_code() <<
      ", strerror=" << YIELD::Exception::strerror( proxy_exception_response.get_platform_error_code() ) << ").";
    }
#endif

    for ( std::vector<org::xtreemfs::interfaces::OSDInterface::writeResponse*>::iterator write_response_i = write_responses.begin(); write_response_i != write_responses.end(); write_response_i++ )
      yidl::Object::decRef( **write_response_i );    

    YIELD::Exception::set_errno( proxy_exception_response.get_platform_error_code() );

    ret = -1;
  }
  catch ( std::exception& exc )
  {
#ifdef _DEBUG
    if ( ( parent_volume->get_flags() & Volume::VOLUME_FLAG_TRACE_FILE_IO ) == Volume::VOLUME_FLAG_TRACE_FILE_IO )
    {
      log->getStream( YIELD::Log::LOG_INFO ) <<
      "xtreemfs::File: write threw std::exception " <<
      "(what=" << exc.what() << ")" <<
      ", setting errno to EIO.";
    }
#endif

    YIELD::Exception::set_errno( EIO );

    ret = -1;
  }

  for ( std::vector<org::xtreemfs::interfaces::OSDInterface::writeResponse*>::iterator write_response_i = write_responses.begin(); write_response_i != write_responses.end(); write_response_i++ )
    yidl::Object::decRef( **write_response_i );    

  return ret;
}
