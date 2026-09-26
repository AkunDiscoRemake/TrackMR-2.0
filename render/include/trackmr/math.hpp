#pragma once
#include <algorithm>
#include <cmath>
#include <cstring>
namespace mr {
struct Vec3 {
    float x,y,z;
    Vec3 operator+(Vec3 b) const { return {x+b.x,y+b.y,z+b.z}; }
    Vec3 operator-(Vec3 b) const { return {x-b.x,y-b.y,z-b.z}; }
    Vec3 operator*(float s) const { return {x*s,y*s,z*s}; }
    float dot(Vec3 b) const { return x*b.x+y*b.y+z*b.z; }
    Vec3 normalized() const { const float n=std::sqrt(dot(*this)); return n>1e-8f?*this*(1/n):Vec3{0,0,-1}; }
};
struct Mat4 {
    float m[16]{};
    static Mat4 identity() { Mat4 a; a.m[0]=a.m[5]=a.m[10]=a.m[15]=1; return a; }
    Mat4 operator*(const Mat4& b) const {
        Mat4 c;
        for(int j=0;j<4;j++) for(int i=0;i<4;i++) for(int k=0;k<4;k++) c.m[j*4+i]+=m[k*4+i]*b.m[j*4+k];
        return c;
    }
    Vec3 direction(Vec3 p) const { return {m[0]*p.x+m[4]*p.y+m[8]*p.z,m[1]*p.x+m[5]*p.y+m[9]*p.z,m[2]*p.x+m[6]*p.y+m[10]*p.z}; }
    Vec3 position() const { return {m[12],m[13],m[14]}; }
    Mat4 inverse() const {
        float a[4][8]{};
        for(int i=0;i<4;i++) { for(int j=0;j<4;j++) a[i][j]=m[j*4+i]; a[i][i+4]=1; }
        for(int col=0;col<4;col++) {
            int pivot=col;
            for(int row=col+1;row<4;row++) if(std::abs(a[row][col])>std::abs(a[pivot][col])) pivot=row;
            if(std::abs(a[pivot][col])<1e-8f) return identity();
            for(int j=0;j<8;j++) std::swap(a[col][j],a[pivot][j]);
            float d=a[col][col]; for(float &v:a[col]) v/=d;
            for(int row=0;row<4;row++) if(row!=col) { float f=a[row][col]; for(int j=0;j<8;j++) a[row][j]-=f*a[col][j]; }
        }
        Mat4 r; for(int i=0;i<4;i++) for(int j=0;j<4;j++) r.m[j*4+i]=a[i][j+4]; return r;
    }
    static Mat4 quaternion(const float* q) {
        float x=q[0],y=q[1],z=q[2],w=q[3]; Mat4 r=identity();
        r.m[0]=1-2*y*y-2*z*z; r.m[1]=2*x*y+2*z*w; r.m[2]=2*x*z-2*y*w;
        r.m[4]=2*x*y-2*z*w; r.m[5]=1-2*x*x-2*z*z; r.m[6]=2*y*z+2*x*w;
        r.m[8]=2*x*z+2*y*w; r.m[9]=2*y*z-2*x*w; r.m[10]=1-2*x*x-2*y*y;
        return r;
    }
};
inline float sphereHit(Vec3 origin, Vec3 direction, Vec3 center, float radius) {
    Vec3 oc=origin-center; float b=oc.dot(direction), c=oc.dot(oc)-radius*radius, d=b*b-c;
    if(d<0) return -1;
    float t=-b-std::sqrt(d); return t>0?t:-1;
}
// Local rectangular surface, flat or curved toward the viewer. u/v match texture input.
inline bool curvedRectHit(Vec3 o,Vec3 d,float width,float height,float radius,float& t,float& u,float& v){
    if(width<=0||height<=0)return false;
    float x;
    if(radius>0){
        const float a=d.x*d.x+d.z*d.z,b=o.x*d.x+(o.z-radius)*d.z;
        const float c=o.x*o.x+(o.z-radius)*(o.z-radius)-radius*radius,disc=b*b-a*c;
        if(a<1e-7f||disc<0)return false;
        t=(-b+std::sqrt(disc))/a;
        auto p=o+d*t;x=radius*std::atan2(p.x,radius-p.z);
    }else{if(std::abs(d.z)<1e-7f)return false;t=-o.z/d.z;x=o.x+d.x*t;}
    u=x/width+.5f;v=.5f-(o.y+d.y*t)/height;
    return t>0&&u>=0&&u<=1&&v>=0&&v<=1;
}
// Shared analytic surface with the GPU: cylinder around origin or flat panel at z=-2.
inline bool panelUv(Vec3 o, Vec3 d, bool curved, float aspect, float& u, float& v) {
    float t=-1, x=0;
    if(curved) {
        float a=d.x*d.x+d.z*d.z, b=o.x*d.x+o.z*d.z, c=o.x*o.x+o.z*o.z-4;
        float disc=b*b-a*c;
        if(a<1e-6f || disc<0) return false;
        t=(-b+std::sqrt(disc))/a;
        Vec3 p=o+d*t; x=std::atan2(p.x,-p.z)*2;
    } else { if(std::abs(d.z)<1e-6f) return false; t=(-2-o.z)/d.z; x=o.x+d.x*t; }
    if(t<=0) return false;
    u=x/std::min(aspect,5.f)+.5f; v=.5f-(o.y+d.y*t);
    return u>=0 && u<=1 && v>=0 && v<=1;
}
}
