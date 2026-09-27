#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <array>
#include <stdexcept>
#include "trackmr/hand_overlay.hpp"
namespace {
GLuint compile(GLenum type,const char* code){
 GLuint s=glCreateShader(type);glShaderSource(s,1,&code,nullptr);glCompileShader(s);
 GLint ok=0;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);if(!ok)throw std::runtime_error("Hand shader compilation failed");return s;
}
}
extern "C" JNIEXPORT jint JNICALL Java_dev_trackmr_validation_NativeOverlayProbe_pixels(JNIEnv* e,jobject,jfloatArray input,jboolean interactive){
 EGLDisplay display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
 EGLSurface surface=EGL_NO_SURFACE;EGLContext context=EGL_NO_CONTEXT;
 int result=-1;
 try{
  if(!eglInitialize(display,nullptr,nullptr))throw std::runtime_error("EGL initialize");
  const EGLint attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,0x40,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
  EGLConfig config;EGLint n=0;
  if(!eglChooseConfig(display,attributes,&config,1,&n)||n<1)throw std::runtime_error("EGL config");
  const EGLint size[]={EGL_WIDTH,192,EGL_HEIGHT,192,EGL_NONE};
  surface=eglCreatePbufferSurface(display,config,size);
  const EGLint version[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};context=eglCreateContext(display,config,EGL_NO_CONTEXT,version);
  if(!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("EGL current");
  GLuint vs=compile(GL_VERTEX_SHADER,mr::lineVertex),fs=compile(GL_FRAGMENT_SHADER,mr::lineFragment);
  GLuint program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glLinkProgram(program);
  GLint linked=0;glGetProgramiv(program,GL_LINK_STATUS,&linked);if(!linked)throw std::runtime_error("Hand program link");
  GLuint vao,buffer;glGenVertexArrays(1,&vao);glGenBuffers(1,&buffer);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,buffer);
  glBufferData(GL_ARRAY_BUFFER,168*sizeof(float),nullptr,GL_DYNAMIC_DRAW);
  glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,nullptr);
  std::array<float,126> landmarks{};std::array<float,168> lines{};
  const int count=input?e->GetArrayLength(input):0;
  if(count>126)throw std::runtime_error("Invalid hand count");
  if(count)e->GetFloatArrayRegion(input,0,count,landmarks.data());
  const int vertices=mr::handLines(count?landmarks.data():nullptr,count,lines.data());
  glViewport(0,0,192,192);glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
  mr::drawHandLines(program,vao,buffer,lines.data(),vertices,interactive);
  std::array<unsigned char,192*192*4> pixels{};glReadPixels(0,0,192,192,GL_RGBA,GL_UNSIGNED_BYTE,pixels.data());
  if(glGetError()!=GL_NO_ERROR)throw std::runtime_error("Hand draw/readback GL error");
  result=0;
  for(int i=0;i<192*192;i++)if(interactive?(pixels[i*4+1]>180&&pixels[i*4]<130):(pixels[i*4]>180&&pixels[i*4+2]<100))result++;
  glDeleteBuffers(1,&buffer);glDeleteVertexArrays(1,&vao);glDeleteProgram(program);glDeleteShader(vs);glDeleteShader(fs);
 }catch(const std::exception& x){e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),x.what());}
 eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
 if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);eglTerminate(display);
 return result;
}
