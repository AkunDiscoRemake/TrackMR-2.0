#pragma once
#include "trackmr/math.hpp"
#include <GLES3/gl3.h>
#include <array>
#include <cmath>

// Independent world-space objects: no Android view or hand backend enters this renderer.
struct SpatialLayer {
 static constexpr int capacity=160,stride=20;
 std::array<float,capacity*stride> data{};
 int count=0;
 GLuint program=0,vao=0,buffer=0;
 GLint vp=-1;
 float pointerX=.5f,pointerY=.5f;
 bool handPointer=false;float hitU=.5f,hitV=.5f;
 mr::Vec3 dockPointer{0,0,-1};
 void initialize(GLuint (*makeProgram)(const char*,const char*)) {
  const char* vs=R"(#version 300 es
precision highp float;
layout(location=1)in vec4 centerWidth;
layout(location=2)in vec4 heightYawOpacityHover;
layout(location=3)in vec4 uvRect;
layout(location=4)in vec4 tintKind;
layout(location=5)in vec4 selectionIdRadius;
uniform mat4 vp;
out vec2 uv;out vec2 local;out vec4 tint;out vec4 style;
void main(){
 const vec2 corners[6]=vec2[6](vec2(0,0),vec2(1,0),vec2(0,1),vec2(0,1),vec2(1,0),vec2(1,1));
 vec2 q=corners[gl_VertexID%6];q.x=(float(gl_VertexID/6)+q.x)/32.;local=q;
 vec2 p=(q-.5)*vec2(centerWidth.w,heightYawOpacityHover.x)*(1.+heightYawOpacityHover.w*.07);
 float yaw=heightYawOpacityHover.y;
 float radius=selectionIdRadius.w;
 float z=0.;if(tintKind.a>2.5&&radius>0.){float angle=p.x/radius;z=radius*(1.-cos(angle));p.x=radius*sin(angle);}
 vec3 w=centerWidth.xyz+vec3(cos(yaw)*p.x+sin(yaw)*z,p.y,-sin(yaw)*p.x+cos(yaw)*z);
 gl_Position=selectionIdRadius.y>.5?vec4(w.xy,0,1):vp*vec4(w,1);
 uv=mix(uvRect.xy+vec2(.5/2048.),uvRect.zw-vec2(.5/2048.),vec2(q.x,1.-q.y));
 tint=tintKind;style=vec4(heightYawOpacityHover.zw,selectionIdRadius.x,selectionIdRadius.w);
})";
  const char* fs=R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 uv;in vec2 local;in vec4 tint;in vec4 style;
uniform sampler2D atlas;uniform samplerExternalOES surfaceContent;uniform mat4 contentTransform;
out vec4 color;
void main(){
 float radius=.12;
 vec2 d=abs(local-.5)-vec2(.5-radius);
 float rounded=length(max(d,0.))+min(max(d.x,d.y),0.)-radius;
 if(rounded>0.)discard;
 vec4 glyph=texture(atlas,uv);
 if(tint.a>2.5){vec2 tex=(contentTransform*vec4(local,0,1)).xy;color=vec4(texture(surfaceContent,tex).rgb,style.x);return;}
 float border=1.-smoothstep(.004,.018,-rounded);
 float glow=style.y*.13+style.z*.12;
 vec3 base=tint.rgb+glow+border*(.13+style.y*.3);
 float alpha=style.x;
 if(tint.a>.5&&tint.a<1.5){color=vec4(glyph.rgb,alpha*glyph.a);return;}
 if(tint.a>1.5){base*=.45;/* Keep informational text readable even when not clickable. */}
 color=vec4(mix(base,glyph.rgb,glyph.a),alpha);
})";
  program=makeProgram(vs,fs);vp=glGetUniformLocation(program,"vp");
  glGenVertexArrays(1,&vao);glGenBuffers(1,&buffer);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,buffer);
  glBufferData(GL_ARRAY_BUFFER,sizeof(data),nullptr,GL_DYNAMIC_DRAW);
  for(int i=0;i<5;i++){glEnableVertexAttribArray(i+1);glVertexAttribPointer(i+1,4,GL_FLOAT,GL_FALSE,stride*sizeof(float),reinterpret_cast<void*>(i*4*sizeof(float)));glVertexAttribDivisor(i+1,1);}
  glUseProgram(program);glUniform1i(glGetUniformLocation(program,"atlas"),1);glUniform1i(glGetUniformLocation(program,"surfaceContent"),2);
 }
 int hit(mr::Vec3 origin,mr::Vec3 direction) {
  float nearest=100;int selected=-1;
  for(int i=0;i<count;i++){
   const auto* d=data.data()+i*stride;if(d[17]>.5f)continue;if(d[6]<.1f||(d[18]<=0&&d[15]==1))continue;
   const float cy=std::cos(d[5]),sy=std::sin(d[5]);
   mr::Vec3 relative=origin-mr::Vec3{d[0],d[1],d[2]};
   mr::Vec3 o{relative.x*cy-relative.z*sy,relative.y,relative.x*sy+relative.z*cy};
   mr::Vec3 ray{direction.x*cy-direction.z*sy,direction.y,direction.x*sy+direction.z*cy};
   float t,u,v;
   if(mr::curvedRectHit(o,ray,d[3]*(1+d[7]*.07f),d[4]*(1+d[7]*.07f),d[15]>2.5f?d[19]:0,t,u,v)&&t<=nearest){nearest=t;selected=(d[18]<=0||d[15]==2)?9999:int(d[18]);hitU=u;hitV=v;}
  }
  return selected;
 }
 void upload(){glBindBuffer(GL_ARRAY_BUFFER,buffer);glBufferSubData(GL_ARRAY_BUFFER,0,count*stride*sizeof(float),data.data());}
 void draw(const mr::Mat4& viewProjection,const float* transform){
  glUseProgram(program);glBindVertexArray(vao);glUniformMatrix4fv(vp,1,GL_FALSE,viewProjection.m);
  glUniformMatrix4fv(glGetUniformLocation(program,"contentTransform"),1,GL_FALSE,transform);
  glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
  glDrawArraysInstanced(GL_TRIANGLES,0,32*6,count);glDisable(GL_BLEND);
 }
 void release(){if(program)glDeleteProgram(program);if(buffer)glDeleteBuffers(1,&buffer);if(vao)glDeleteVertexArrays(1,&vao);}
};
