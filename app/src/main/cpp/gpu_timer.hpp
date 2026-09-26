#pragma once
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <EGL/egl.h>
#include <array>
#include <cstring>
/** Nonblocking GPU elapsed queries. Unsupported/disjoint is -1, never a fabricated zero. */
class GpuTimer {
 PFNGLGENQUERIESEXTPROC gen=nullptr;PFNGLDELETEQUERIESEXTPROC del=nullptr;
 PFNGLBEGINQUERYEXTPROC beginQuery=nullptr;PFNGLENDQUERYEXTPROC endQuery=nullptr;
 PFNGLGETQUERYOBJECTUIVEXTPROC available=nullptr;PFNGLGETQUERYOBJECTUI64VEXTPROC result=nullptr;
 std::array<GLuint,4> ids{};std::array<bool,4> pending{};int next=0;bool started=false;
public:
 float milliseconds=-1;
 void initialize(){
  auto extensions=reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));if(!extensions||!std::strstr(extensions,"GL_EXT_disjoint_timer_query"))return;
  gen=reinterpret_cast<PFNGLGENQUERIESEXTPROC>(eglGetProcAddress("glGenQueriesEXT"));del=reinterpret_cast<PFNGLDELETEQUERIESEXTPROC>(eglGetProcAddress("glDeleteQueriesEXT"));
  beginQuery=reinterpret_cast<PFNGLBEGINQUERYEXTPROC>(eglGetProcAddress("glBeginQueryEXT"));endQuery=reinterpret_cast<PFNGLENDQUERYEXTPROC>(eglGetProcAddress("glEndQueryEXT"));
  available=reinterpret_cast<PFNGLGETQUERYOBJECTUIVEXTPROC>(eglGetProcAddress("glGetQueryObjectuivEXT"));result=reinterpret_cast<PFNGLGETQUERYOBJECTUI64VEXTPROC>(eglGetProcAddress("glGetQueryObjectui64vEXT"));
  if(gen&&del&&beginQuery&&endQuery&&available&&result)gen(ids.size(),ids.data());else gen=nullptr;
 }
 void begin(){
  if(!gen)return;GLint disjoint=0;glGetIntegerv(GL_GPU_DISJOINT_EXT,&disjoint);
  for(int i=0;i<4;i++)if(pending[i]){GLuint ready=0;available(ids[i],GL_QUERY_RESULT_AVAILABLE_EXT,&ready);if(ready){GLuint64 nanos=0;result(ids[i],GL_QUERY_RESULT_EXT,&nanos);pending[i]=false;milliseconds=disjoint?-1:static_cast<float>(nanos/1e6);}}
  if(disjoint)milliseconds=-1;
  if(!pending[next]){beginQuery(GL_TIME_ELAPSED_EXT,ids[next]);started=true;}
 }
 void end(){if(started){endQuery(GL_TIME_ELAPSED_EXT);pending[next]=true;next=(next+1)%4;started=false;}}
 void release(){if(gen&&del)del(ids.size(),ids.data());gen=nullptr;}
};
