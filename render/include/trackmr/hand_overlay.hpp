#pragma once
#include <GLES3/gl3.h>
#include <cmath>
namespace mr {
inline const char* lineVertex=R"(#version 300 es
layout(location=0) in vec2 position;
void main(){gl_Position=vec4(position,0,1);gl_PointSize=6.;}
)";
inline const char* lineFragment=R"(#version 300 es
precision mediump float;
out vec4 color;
uniform vec3 lineColor;
void main(){color=vec4(lineColor,1.);}
)";

inline int handLines(const float* points,int count,float* out){
 if(!points||(count!=63&&count!=126))return 0;
 for(int i=0;i<count;i++)if(!std::isfinite(points[i]))return 0;
 const int edges[][2]={{0,1},{1,2},{2,3},{3,4},{0,5},{5,6},{6,7},{7,8},{5,9},{9,10},{10,11},{11,12},{9,13},{13,14},{14,15},{15,16},{13,17},{0,17},{17,18},{18,19},{19,20}};
 int vertices=0;
 for(int h=0;h<count/63;h++)for(auto& edge:edges)for(int index:edge){
  const int n=vertices++*2;out[n]=points[h*63+index*3]*2-1;out[n+1]=1-points[h*63+index*3+1]*2;
 }
 return vertices;
}
// Shared by the shipped renderer and the Android EGL/readback regression. Current eye FBO/viewport belong to caller.
inline void drawHandLines(GLuint program,GLuint vao,GLuint buffer,const float* points,int vertices,bool interactive){
 if(vertices<=0)return;
 glDisable(GL_DEPTH_TEST);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
 glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,buffer);
 glUniform3f(glGetUniformLocation(program,"lineColor"),interactive?.25f:1.f,interactive?1.f:.65f,interactive?.85f:.1f);
 glBufferSubData(GL_ARRAY_BUFFER,0,vertices*2*sizeof(float),points);
 glLineWidth(2);glDrawArrays(GL_LINES,0,vertices);glDrawArrays(GL_POINTS,0,vertices);
}
} // namespace mr
