#include <jni.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <time.h>
#include <string>
#include <array>
#include <stdexcept>
#include "cardboard.h"
#include "math.hpp"
using namespace mr;
namespace {
JavaVM* vm=nullptr;
int64_t bootNs() { timespec t{}; clock_gettime(CLOCK_BOOTTIME,&t); return int64_t(t.tv_sec)*1000000000LL+t.tv_nsec; }
const char* vertex=R"(#version 300 es
precision highp float;
out vec2 ndc;
void main(){ vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); ndc=p*2.0-1.0; gl_Position=vec4(ndc,0,1); }
)";
const char* fragment=R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
in vec2 ndc;
out vec4 color;
uniform mat4 invProjection,world,captureTransform;
uniform sampler2D panorama,panel;
uniform samplerExternalOES capture;
uniform int scene,curved,capturing,highlight,target;
uniform float aspect;
uniform vec4 balls[6];
void main(){
 vec4 p=invProjection*vec4(ndc,1,1);
 vec3 d=normalize(mat3(world)*(p.xyz/p.w));
 vec3 o=world[3].xyz;
 vec2 uv=vec2(atan(d.x,-d.z)/6.2831853+.5,.5-asin(clamp(d.y,-1.,1.))/3.14159265);
 vec3 c=texture(panorama,uv).rgb;
 if(scene==0){
   float t=-1.,x=0.;
   if(curved==1){
     float a=dot(d.xz,d.xz), b=dot(o.xz,d.xz), z=dot(o.xz,o.xz)-4.,disc=b*b-a*z;
     if(a>0.000001&&disc>=0.){t=(-b+sqrt(disc))/a;vec3 h=o+d*t;x=atan(h.x,-h.z)*2.;}
   }else if(abs(d.z)>.000001){t=(-2.-o.z)/d.z;x=o.x+d.x*t;}
   vec2 q=vec2(x/min(aspect,5.)+.5,.5-(o.y+d.y*t));
   if(t>0.&&all(greaterThanEqual(q,vec2(0)))&&all(lessThanEqual(q,vec2(1)))){
     vec4 layer;
     if(capturing==1){vec2 tc=(captureTransform*vec4(q.x,1.-q.y,0,1)).xy;layer=vec4(texture(capture,tc).rgb,1.);}
     else layer=texture(panel,q);
     c=mix(c,layer.rgb,layer.a);
   }
 }else{
   float nearest=1000.;
   for(int i=0;i<6;i++){
     vec3 oc=o-balls[i].xyz;float b=dot(oc,d),disc=b*b-dot(oc,oc)+balls[i].w*balls[i].w;
     if(disc>=0.){float t=-b-sqrt(disc);if(t>0.&&t<nearest){
       nearest=t;vec3 n=normalize(o+d*t-balls[i].xyz);
       vec3 base=i==target?vec3(.4,1.,.82):vec3(.56,.37,1.);
       float light=.28+.72*max(0.,dot(n,normalize(vec3(-1,2,1))));
       float ring=1.-smoothstep(.025,.05,abs(n.y));
       c=base*light+ring*.25+pow(1.-max(0.,dot(n,-d)),3.)*.3;
       if(i==highlight)c+=vec3(.16);
     }}
   }
 }
 color=vec4(c,1);
}
)";
const char* lineVertex=R"(#version 300 es
layout(location=0) in vec2 position;
void main(){gl_Position=vec4(position,0,1);}
)";
const char* lineFragment=R"(#version 300 es
precision mediump float;
out vec4 color;
void main(){color=vec4(.45,1.,.9,1.);}
)";
GLuint shader(GLenum type,const char* code){
 GLuint s=glCreateShader(type);glShaderSource(s,1,&code,nullptr);glCompileShader(s);
 GLint ok;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
 if(!ok){char msg[2048];glGetShaderInfoLog(s,sizeof(msg),nullptr,msg);glDeleteShader(s);throw std::runtime_error(msg);}return s;
}
GLuint program(const char* vs,const char* fs){
 GLuint v=shader(GL_VERTEX_SHADER,vs),f=shader(GL_FRAGMENT_SHADER,fs),p=glCreateProgram();
 glAttachShader(p,v);glAttachShader(p,f);glLinkProgram(p);glDeleteShader(v);glDeleteShader(f);
 GLint ok;glGetProgramiv(p,GL_LINK_STATUS,&ok);if(!ok){glDeleteProgram(p);throw std::runtime_error("Shader link failed");}return p;
}
struct Renderer {
 CardboardHeadTracker* tracker=nullptr;
 CardboardLensDistortion* lens=nullptr;
 CardboardDistortionRenderer* distortion=nullptr;
 GLuint prog=0,lineProg=0,vao=0,lineVao=0,lineBuffer=0,fb=0,texture=0;
 GLint invLoc,worldLoc,transformLoc,sceneLoc,curveLoc,captureLoc,aspectLoc,ballsLoc,highlightLoc,targetLoc;
 int width=0,height=0,rw=0,rh=0,scene=0,score=0,hover=-1;
 float scale=1,aspect=2.4f;
 bool curved=true,dirty=true;
 Mat4 projections[2],eyes[2],world=Mat4::identity();
 CardboardEyeTextureDescription descriptions[2]{};
 std::array<float,24> balls{};
 std::array<float,168> handLines{};
 int handVertices=0;
 Renderer(JNIEnv* env,jobject activity){
   Cardboard_initializeAndroid(vm,activity);
   tracker=CardboardHeadTracker_create();
   (void)env;
 }
 ~Renderer(){
   CardboardHeadTracker_destroy(tracker);CardboardLensDistortion_destroy(lens);
   CardboardDistortionRenderer_destroy(distortion);
   if(prog)glDeleteProgram(prog);if(lineProg)glDeleteProgram(lineProg);
   if(fb)glDeleteFramebuffers(1,&fb);if(texture)glDeleteTextures(1,&texture);
   if(vao)glDeleteVertexArrays(1,&vao);if(lineVao)glDeleteVertexArrays(1,&lineVao);
   if(lineBuffer)glDeleteBuffers(1,&lineBuffer);
 }
 // Called on every new EGL context. Old GL names must not be deleted in the new context.
 void surface(){
   fb=texture=0; prog=program(vertex,fragment);lineProg=program(lineVertex,lineFragment);
   glGenVertexArrays(1,&vao);glGenVertexArrays(1,&lineVao);glGenBuffers(1,&lineBuffer);
   glBindVertexArray(lineVao);glBindBuffer(GL_ARRAY_BUFFER,lineBuffer);
   glBufferData(GL_ARRAY_BUFFER,sizeof(handLines),nullptr,GL_DYNAMIC_DRAW);
   glEnableVertexAttribArray(0);glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,nullptr);
   invLoc=glGetUniformLocation(prog,"invProjection");worldLoc=glGetUniformLocation(prog,"world");
   transformLoc=glGetUniformLocation(prog,"captureTransform");sceneLoc=glGetUniformLocation(prog,"scene");
   curveLoc=glGetUniformLocation(prog,"curved");captureLoc=glGetUniformLocation(prog,"capturing");
   aspectLoc=glGetUniformLocation(prog,"aspect");ballsLoc=glGetUniformLocation(prog,"balls");
   highlightLoc=glGetUniformLocation(prog,"highlight");targetLoc=glGetUniformLocation(prog,"target");
   glUseProgram(prog);glUniform1i(glGetUniformLocation(prog,"panorama"),0);
   glUniform1i(glGetUniformLocation(prog,"panel"),1);glUniform1i(glGetUniformLocation(prog,"capture"),2);
   // VrActivity creates a fresh renderer for each EGL context.
   dirty=true;
 }
 bool configure(){
   if(width<=0||height<=0)return false;
   if(!dirty)return true;
   CardboardDistortionRenderer_destroy(distortion);distortion=nullptr;
   CardboardLensDistortion_destroy(lens);lens=nullptr;
   uint8_t* params=nullptr;int size=0;CardboardQrCode_getSavedDeviceParams(&params,&size);
   bool owned=size>0;
   if(!owned) CardboardQrCode_getCardboardV1DeviceParams(&params,&size);
   lens=CardboardLensDistortion_create(params,size,width,height);
   if(owned)CardboardQrCode_destroy(params);
   if(!lens)return false;
   CardboardOpenGlEsDistortionRendererConfig cfg{kGlTexture2D};
   distortion=CardboardOpenGlEs3DistortionRenderer_create(&cfg);
   for(int i=0;i<2;i++){
     auto eye=static_cast<CardboardEye>(i);CardboardMesh mesh{};
     CardboardLensDistortion_getDistortionMesh(lens,eye,&mesh);
     CardboardDistortionRenderer_setMesh(distortion,&mesh,eye);
     CardboardLensDistortion_getProjectionMatrix(lens,eye,.05f,100.f,projections[i].m);
     projections[i]=projections[i].inverse();
     CardboardLensDistortion_getEyeFromHeadMatrix(lens,eye,eyes[i].m);eyes[i]=eyes[i].inverse();
   }
   if(fb)glDeleteFramebuffers(1,&fb);if(texture)glDeleteTextures(1,&texture);
   rw=std::max(2,int(width*scale)/2*2);rh=std::max(2,int(height*scale));
   glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
   glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
   glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
   glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,rw,rh,0,GL_RGBA,GL_UNSIGNED_BYTE,nullptr);
   glGenFramebuffers(1,&fb);glBindFramebuffer(GL_FRAMEBUFFER,fb);
   glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,texture,0);
   if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw std::runtime_error("Stereo framebuffer incomplete");
   for(int i=0;i<2;i++){descriptions[i].texture=texture;descriptions[i].left_u=i*.5f;descriptions[i].right_u=(i+1)*.5f;descriptions[i].top_v=1;descriptions[i].bottom_v=0;}
   dirty=false;return true;
 }
 int draw(GLuint sky,GLuint panel,GLuint external,const float* transform,bool capturing,const float* ar,int64_t predictionNs){
   if(!configure())return -1;
   float position[3],q[4];
   // Fetch pose as late as possible, after texture uploads and ARCore work.
   CardboardHeadTracker_getPose(tracker,bootNs()+std::clamp<int64_t>(predictionNs,0,30000000),kLandscapeLeft,position,q);
   world=Mat4::quaternion(q).inverse(); // Cardboard sample exposes world-to-head rotation.
   if(ar)std::memcpy(world.m,ar,16*sizeof(float));
   Vec3 origin=world.position(),dir=world.direction({0,0,-1}).normalized();hover=-1;
   float u,v;
   if(scene==0&&!capturing&&panelUv(origin,dir,curved,aspect,u,v)&&v>.52f&&v<.9f)hover=100+std::min(4,int(u*5));
   double time=bootNs()/1e9;
   float closest=100;
   for(int i=0;i<6;i++){
     float phase=float(i)*1.0472f;
     Vec3 pos{std::sin(phase)*1.15f,std::cos(phase)*.6f,-2.7f};
     if(scene==2){pos.x+=.3f*std::sin(float(time)*1.4f+i);pos.y+=.25f*std::cos(float(time)+i);}
     if(scene==1)pos.z-=.3f*std::sin(float(score)+i);
     balls[i*4]=pos.x;balls[i*4+1]=pos.y;balls[i*4+2]=pos.z;balls[i*4+3]=.16f;
     float hit=sphereHit(origin,dir,pos,.16f);if(scene&&hit>0&&hit<closest){closest=hit;hover=i;}
   }
   glBindFramebuffer(GL_FRAMEBUFFER,fb);glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);glDisable(GL_SCISSOR_TEST);
   glUseProgram(prog);glBindVertexArray(vao);
   glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,sky);
   glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,panel);
   glActiveTexture(GL_TEXTURE2);glBindTexture(0x8D65,external);
   glUniform1i(sceneLoc,scene);glUniform1i(curveLoc,curved);glUniform1i(captureLoc,capturing);
   glUniform1f(aspectLoc,aspect);glUniform1i(highlightLoc,hover);glUniform1i(targetLoc,score%6);
   glUniform4fv(ballsLoc,6,balls.data());glUniformMatrix4fv(transformLoc,1,GL_FALSE,transform);
   for(int i=0;i<2;i++){
     glViewport(i*rw/2,0,rw/2,rh);glUseProgram(prog);glBindVertexArray(vao);
     Mat4 eyeWorld=world*eyes[i];
     glUniformMatrix4fv(invLoc,1,GL_FALSE,projections[i].m);glUniformMatrix4fv(worldLoc,1,GL_FALSE,eyeWorld.m);
     glDrawArrays(GL_TRIANGLES,0,3);
     // Camera-normalized visualizer, NOT metric stereo hand reconstruction.
     if(handVertices){glUseProgram(lineProg);glBindVertexArray(lineVao);glBindBuffer(GL_ARRAY_BUFFER,lineBuffer);
       glBufferSubData(GL_ARRAY_BUFFER,0,handVertices*2*sizeof(float),handLines.data());glLineWidth(1);glDrawArrays(GL_LINES,0,handVertices);}
     // Gaze reticle, one physical pixel wide. No texture or extra material allocation.
     float cross[]={-.008f,0,.008f,0,0,-.008f,0,.008f};
     glUseProgram(lineProg);glBindVertexArray(lineVao);glBindBuffer(GL_ARRAY_BUFFER,lineBuffer);
     glBufferSubData(GL_ARRAY_BUFFER,0,sizeof(cross),cross);glDrawArrays(GL_LINES,0,4);
   }
   CardboardDistortionRenderer_renderEyeToDisplay(distortion,0,0,0,width,height,&descriptions[0],&descriptions[1]);
   return hover;
 }
};
Renderer* ptr(jlong p){return reinterpret_cast<Renderer*>(p);}
void error(JNIEnv* e,const std::exception& x){__android_log_print(ANDROID_LOG_ERROR,"TrackMR","%s",x.what());e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),x.what());}
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* v,void*){vm=v;return JNI_VERSION_1_6;}
#define JNI(name) extern "C" JNIEXPORT
JNI(create) jlong JNICALL Java_dev_trackmr_vr_NativeBridge_create(JNIEnv* e,jobject,jobject a){return reinterpret_cast<jlong>(new Renderer(e,a));}
JNI(destroy) void JNICALL Java_dev_trackmr_vr_NativeBridge_destroy(JNIEnv*,jobject,jlong p){delete ptr(p);}
JNI(surface) void JNICALL Java_dev_trackmr_vr_NativeBridge_surface(JNIEnv* e,jobject,jlong p){try{ptr(p)->surface();}catch(const std::exception& x){error(e,x);}}
JNI(resize) void JNICALL Java_dev_trackmr_vr_NativeBridge_resize(JNIEnv*,jobject,jlong p,jint w,jint h){auto r=ptr(p);r->width=w;r->height=h;r->dirty=true;}
JNI(resume) void JNICALL Java_dev_trackmr_vr_NativeBridge_resume(JNIEnv*,jobject,jlong p){CardboardHeadTracker_resume(ptr(p)->tracker);ptr(p)->dirty=true;}
JNI(pause) void JNICALL Java_dev_trackmr_vr_NativeBridge_pause(JNIEnv*,jobject,jlong p){CardboardHeadTracker_pause(ptr(p)->tracker);}
JNI(scan) void JNICALL Java_dev_trackmr_vr_NativeBridge_scan(JNIEnv*,jobject){CardboardQrCode_scanQrCodeAndSaveDeviceParams();}
JNI(recenter) void JNICALL Java_dev_trackmr_vr_NativeBridge_recenter(JNIEnv*,jobject,jlong p){CardboardHeadTracker_recenter(ptr(p)->tracker);}
JNI(settings) void JNICALL Java_dev_trackmr_vr_NativeBridge_settings(JNIEnv*,jobject,jlong p,jfloat scale,jboolean curve,jfloat aspect){auto r=ptr(p);if(std::abs(r->scale-scale)>.01f){r->scale=std::clamp(scale,.6f,1.f);r->dirty=true;}r->curved=curve;r->aspect=std::clamp(aspect,.3f,5.f);}
JNI(scene) void JNICALL Java_dev_trackmr_vr_NativeBridge_scene(JNIEnv*,jobject,jlong p,jint scene){ptr(p)->scene=std::clamp(scene,0,3);ptr(p)->score=0;}
JNI(select) jint JNICALL Java_dev_trackmr_vr_NativeBridge_select(JNIEnv*,jobject,jlong p){auto r=ptr(p);if(r->scene&&r->hover>=0&&r->hover<6){if(r->scene!=3||r->hover==r->score%6)r->score++;else r->score=0;}return r->score;}
JNI(hands) void JNICALL Java_dev_trackmr_vr_NativeBridge_hands(JNIEnv* e,jobject,jlong p,jfloatArray points){
 auto r=ptr(p);r->handVertices=0;if(!points||e->GetArrayLength(points)!=63)return;
 float data[63];e->GetFloatArrayRegion(points,0,63,data);
 const int edges[][2]={{0,1},{1,2},{2,3},{3,4},{0,5},{5,6},{6,7},{7,8},{5,9},{9,10},{10,11},{11,12},{9,13},{13,14},{14,15},{15,16},{13,17},{0,17},{17,18},{18,19},{19,20}};
 for(auto& edge:edges)for(int index:edge){int n=r->handVertices++*2;r->handLines[n]=data[index*3]*2-1;r->handLines[n+1]=1-data[index*3+1]*2;}
}
JNI(draw) jint JNICALL Java_dev_trackmr_vr_NativeBridge_draw(JNIEnv* e,jobject,jlong p,jint sky,jint panel,jint external,jfloatArray transform,jboolean capture,jfloatArray ar,jlong prediction){
 float t[16],pose[16];e->GetFloatArrayRegion(transform,0,16,t);if(ar)e->GetFloatArrayRegion(ar,0,16,pose);
 try{return ptr(p)->draw(sky,panel,external,t,capture,ar?pose:nullptr,prediction);}catch(const std::exception& x){error(e,x);return -1;}
}
