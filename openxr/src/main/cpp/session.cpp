#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <thread>
#include <vector>
#include <array>
#include <string>
#include <sstream>
#include <cstring>
#include <algorithm>
#include <stdexcept>
#include "trackmr/math.hpp"
#include "frame_protocol.hpp"

namespace {
using Clock=std::chrono::steady_clock;
struct Swapchain { XrSwapchain handle=XR_NULL_HANDLE;int32_t width=0,height=0;std::vector<XrSwapchainImageOpenGLESKHR> images; };
struct Session {
 std::atomic<bool> stop{false};
 XrInstance instance=XR_NULL_HANDLE;XrSystemId system=XR_NULL_SYSTEM_ID;XrSession session=XR_NULL_HANDLE;
 XrSpace base=XR_NULL_HANDLE,head=XR_NULL_HANDLE;XrSessionState state=XR_SESSION_STATE_UNKNOWN;
 XrActionSet actions=XR_NULL_HANDLE;XrAction select=XR_NULL_HANDLE,grip=XR_NULL_HANDLE,haptic=XR_NULL_HANDLE;
 std::array<XrPath,2> handPaths{};std::array<XrSpace,2> gripSpaces{};std::array<XrHandTrackerEXT,2> trackers{};
 PFN_xrCreateHandTrackerEXT createHand=nullptr;PFN_xrDestroyHandTrackerEXT destroyHand=nullptr;PFN_xrLocateHandJointsEXT locateHand=nullptr;
 XrEnvironmentBlendMode blend=XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
 bool handsSupported=false,running=false,stage=false;
 std::vector<Swapchain> chains;std::vector<XrView> views;std::vector<XrCompositionLayerProjectionView> projectionViews;
 EGLDisplay display=EGL_NO_DISPLAY;EGLContext context=EGL_NO_CONTEXT;EGLSurface surface=EGL_NO_SURFACE;EGLConfig config=nullptr;
 GLuint framebuffer=0,program=0,vao=0,buffer=0;
 uint64_t frames=0,submitted=0,selects=0;uint32_t validJoints=0;
 std::ostringstream report;
 FrameProtocol protocol;
 void note(const std::string& s){report<<s<<'\n';__android_log_print(ANDROID_LOG_INFO,"TrackMR-OpenXR","%s",s.c_str());}
 std::string resultName(XrResult r){char name[XR_MAX_RESULT_STRING_SIZE]{};if(instance&&XR_SUCCEEDED(xrResultToString(instance,r,name)))return name;return std::to_string(r);}
 void check(XrResult result,const char* operation){if(XR_FAILED(result))throw std::runtime_error(std::string(operation)+": "+resultName(result)+" ("+std::to_string(result)+")");}
 template<class T> T function(const char* name){PFN_xrVoidFunction fn=nullptr;check(xrGetInstanceProcAddr(instance,name,&fn),name);return reinterpret_cast<T>(fn);}
 XrPath path(const char* value){XrPath p;check(xrStringToPath(instance,value,&p),value);return p;}
 void initialize(JNIEnv* env,jobject activity){
  JavaVM* vm=nullptr;env->GetJavaVM(&vm);
  PFN_xrInitializeLoaderKHR initialize=nullptr;
  check(xrGetInstanceProcAddr(XR_NULL_HANDLE,"xrInitializeLoaderKHR",reinterpret_cast<PFN_xrVoidFunction*>(&initialize)),"xrGetInstanceProcAddr loader");
  if(!initialize)throw std::runtime_error("Loader initialization unavailable");
  XrLoaderInitInfoAndroidKHR init{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};init.applicationVM=vm;init.applicationContext=activity;
  check(initialize(reinterpret_cast<XrLoaderInitInfoBaseHeaderKHR*>(&init)),"xrInitializeLoaderKHR");
  uint32_t count=0;check(xrEnumerateInstanceExtensionProperties(nullptr,0,&count,nullptr),"enumerate extensions count");
  std::vector<XrExtensionProperties> extensions(count,{XR_TYPE_EXTENSION_PROPERTIES});check(xrEnumerateInstanceExtensionProperties(nullptr,count,&count,extensions.data()),"enumerate extensions");
  auto has=[&](const char* name){return std::any_of(extensions.begin(),extensions.end(),[&](const auto& e){return std::strcmp(name,e.extensionName)==0;});};
  std::vector<const char*> enabled{XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
  for(auto e:enabled)if(!has(e))throw std::runtime_error(std::string("Runtime missing required extension ")+e);
  handsSupported=has(XR_EXT_HAND_TRACKING_EXTENSION_NAME);if(handsSupported)enabled.push_back(XR_EXT_HAND_TRACKING_EXTENSION_NAME);
  XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};android.applicationVM=vm;android.applicationActivity=activity;
  XrInstanceCreateInfo info{XR_TYPE_INSTANCE_CREATE_INFO};info.next=&android;
  std::strncpy(info.applicationInfo.applicationName,"TrackMR spatial client",XR_MAX_APPLICATION_NAME_SIZE-1);info.applicationInfo.applicationVersion=2;info.applicationInfo.apiVersion=XR_MAKE_VERSION(1,0,0);
  info.enabledExtensionCount=static_cast<uint32_t>(enabled.size());info.enabledExtensionNames=enabled.data();check(xrCreateInstance(&info,&instance),"xrCreateInstance");
  XrInstanceProperties properties{XR_TYPE_INSTANCE_PROPERTIES};check(xrGetInstanceProperties(instance,&properties),"xrGetInstanceProperties");note(std::string("Runtime: ")+properties.runtimeName);
  XrSystemGetInfo getSystem{XR_TYPE_SYSTEM_GET_INFO};getSystem.formFactor=XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;check(xrGetSystem(instance,&getSystem,&system),"xrGetSystem HMD");
  XrSystemHandTrackingPropertiesEXT handProperties{XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT};XrSystemProperties systemProperties{XR_TYPE_SYSTEM_PROPERTIES};if(handsSupported)systemProperties.next=&handProperties;
  check(xrGetSystemProperties(instance,system,&systemProperties),"xrGetSystemProperties");handsSupported=handsSupported&&handProperties.supportsHandTracking;
  XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
  check(function<PFN_xrGetOpenGLESGraphicsRequirementsKHR>("xrGetOpenGLESGraphicsRequirementsKHR")(instance,system,&requirements),"xrGetOpenGLESGraphicsRequirementsKHR");
  if(requirements.minApiVersionSupported>XR_MAKE_VERSION(3,0,0)||requirements.maxApiVersionSupported<XR_MAKE_VERSION(3,0,0))throw std::runtime_error("Runtime does not accept GLES 3.0");
  createEgl();
  XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};binding.display=display;binding.config=config;binding.context=context;
  XrSessionCreateInfo createSession{XR_TYPE_SESSION_CREATE_INFO};createSession.next=&binding;createSession.systemId=system;check(xrCreateSession(instance,&createSession,&session),"xrCreateSession");
  uint32_t n=0;check(xrEnumerateReferenceSpaces(session,0,&n,nullptr),"enumerate spaces");std::vector<XrReferenceSpaceType> spaces(n);check(xrEnumerateReferenceSpaces(session,n,&n,spaces.data()),"enumerate spaces values");
  stage=std::find(spaces.begin(),spaces.end(),XR_REFERENCE_SPACE_TYPE_STAGE)!=spaces.end();
  XrReferenceSpaceCreateInfo space{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};space.poseInReferenceSpace.orientation.w=1;space.referenceSpaceType=stage?XR_REFERENCE_SPACE_TYPE_STAGE:XR_REFERENCE_SPACE_TYPE_LOCAL;check(xrCreateReferenceSpace(session,&space,&base),"xrCreateReferenceSpace base");
  space.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_VIEW;check(xrCreateReferenceSpace(session,&space,&head),"xrCreateReferenceSpace view");
  createActions();createSwapchains();createGraphics();
  if(handsSupported){
   createHand=function<PFN_xrCreateHandTrackerEXT>("xrCreateHandTrackerEXT");destroyHand=function<PFN_xrDestroyHandTrackerEXT>("xrDestroyHandTrackerEXT");locateHand=function<PFN_xrLocateHandJointsEXT>("xrLocateHandJointsEXT");
   for(int i=0;i<2;i++){XrHandTrackerCreateInfoEXT h{XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT};h.hand=i==0?XR_HAND_LEFT_EXT:XR_HAND_RIGHT_EXT;h.handJointSet=XR_HAND_JOINT_SET_DEFAULT_EXT;XrResult r=createHand(session,&h,&trackers[i]);if(XR_FAILED(r)){trackers[i]=XR_NULL_HANDLE;note("Optional hand tracker: "+resultName(r));}}
  }
  note("Real GLES session, reference spaces, swapchains and actions created");
 }
 void createEgl(){
  display=eglGetDisplay(EGL_DEFAULT_DISPLAY);if(display==EGL_NO_DISPLAY||!eglInitialize(display,nullptr,nullptr))throw std::runtime_error("eglInitialize failed");
  if(!eglBindAPI(EGL_OPENGL_ES_API))throw std::runtime_error("eglBindAPI failed");
  const EGLint attributes[]={EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};EGLint count=0;
  if(!eglChooseConfig(display,attributes,&config,1,&count)||count!=1)throw std::runtime_error("No GLES3 EGLConfig");
  const EGLint version[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};context=eglCreateContext(display,config,EGL_NO_CONTEXT,version);
  const EGLint size[]={EGL_WIDTH,16,EGL_HEIGHT,16,EGL_NONE};surface=eglCreatePbufferSurface(display,config,size);
  if(context==EGL_NO_CONTEXT||surface==EGL_NO_SURFACE||!eglMakeCurrent(display,surface,surface,context))throw std::runtime_error("OpenXR EGL binding failed");
 }
 void createSwapchains(){
  uint32_t count=0;check(xrEnumerateViewConfigurations(instance,system,0,&count,nullptr),"enumerate view configs");std::vector<XrViewConfigurationType> configs(count);check(xrEnumerateViewConfigurations(instance,system,count,&count,configs.data()),"view configs");
  if(std::find(configs.begin(),configs.end(),XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO)==configs.end())throw std::runtime_error("Runtime does not expose primary stereo");
  check(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,0,&count,nullptr),"enumerate views count");
  if(count!=2)throw std::runtime_error("Primary stereo must expose two views");
  std::vector<XrViewConfigurationView> configViews(count,{XR_TYPE_VIEW_CONFIGURATION_VIEW});check(xrEnumerateViewConfigurationViews(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,count,&count,configViews.data()),"view dimensions");
  views.resize(count,{XR_TYPE_VIEW});projectionViews.resize(count,{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW});chains.resize(count);
  uint32_t formatsCount=0;check(xrEnumerateSwapchainFormats(session,0,&formatsCount,nullptr),"swapchain formats count");std::vector<int64_t> formats(formatsCount);check(xrEnumerateSwapchainFormats(session,formatsCount,&formatsCount,formats.data()),"swapchain formats");
  int64_t format=0;for(auto preferred:{GL_SRGB8_ALPHA8,GL_RGBA8})if(std::find(formats.begin(),formats.end(),preferred)!=formats.end()){format=preferred;break;}
  if(!format)throw std::runtime_error("No compatible RGBA8 swapchain format");
  for(size_t i=0;i<chains.size();i++){
   auto& c=chains[i];c.width=static_cast<int32_t>(configViews[i].recommendedImageRectWidth);c.height=static_cast<int32_t>(configViews[i].recommendedImageRectHeight);
   XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};info.usageFlags=XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT|XR_SWAPCHAIN_USAGE_SAMPLED_BIT;info.format=format;info.sampleCount=1;info.width=c.width;info.height=c.height;info.faceCount=1;info.arraySize=1;info.mipCount=1;
   check(xrCreateSwapchain(session,&info,&c.handle),"xrCreateSwapchain");uint32_t n=0;check(xrEnumerateSwapchainImages(c.handle,0,&n,nullptr),"enumerate swapchain images count");
   c.images.resize(n,{XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});check(xrEnumerateSwapchainImages(c.handle,n,&n,reinterpret_cast<XrSwapchainImageBaseHeader*>(c.images.data())),"enumerate swapchain images");
  }
  uint32_t blendsCount=0;check(xrEnumerateEnvironmentBlendModes(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,0,&blendsCount,nullptr),"blend modes count");std::vector<XrEnvironmentBlendMode> blends(blendsCount);check(xrEnumerateEnvironmentBlendModes(instance,system,XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,blendsCount,&blendsCount,blends.data()),"blend modes");
  if(blends.empty())throw std::runtime_error("Runtime has no blend mode");blend=std::find(blends.begin(),blends.end(),XR_ENVIRONMENT_BLEND_MODE_OPAQUE)!=blends.end()?XR_ENVIRONMENT_BLEND_MODE_OPAQUE:blends[0];
 }
 void createActions(){
  handPaths={path("/user/hand/left"),path("/user/hand/right")};
  XrActionSetCreateInfo set{XR_TYPE_ACTION_SET_CREATE_INFO};std::strcpy(set.actionSetName,"trackmr");std::strcpy(set.localizedActionSetName,"TrackMR interaction");check(xrCreateActionSet(instance,&set,&actions),"xrCreateActionSet");
  auto create=[&](const char* name,XrActionType type,XrAction& result){XrActionCreateInfo a{XR_TYPE_ACTION_CREATE_INFO};a.actionType=type;std::strcpy(a.actionName,name);std::strcpy(a.localizedActionName,name);a.countSubactionPaths=2;a.subactionPaths=handPaths.data();check(xrCreateAction(actions,&a,&result),"xrCreateAction");};
  create("select",XR_ACTION_TYPE_BOOLEAN_INPUT,select);create("grip",XR_ACTION_TYPE_POSE_INPUT,grip);create("feedback",XR_ACTION_TYPE_VIBRATION_OUTPUT,haptic);
  std::vector<XrActionSuggestedBinding> bindings;
  for(auto hand:{"/user/hand/left","/user/hand/right"}){bindings.push_back({select,path((std::string(hand)+"/input/select/click").c_str())});bindings.push_back({grip,path((std::string(hand)+"/input/grip/pose").c_str())});bindings.push_back({haptic,path((std::string(hand)+"/output/haptic").c_str())});}
  XrInteractionProfileSuggestedBinding suggestion{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};suggestion.interactionProfile=path("/interaction_profiles/khr/simple_controller");suggestion.countSuggestedBindings=bindings.size();suggestion.suggestedBindings=bindings.data();
  XrResult r=xrSuggestInteractionProfileBindings(instance,&suggestion);if(XR_FAILED(r))note("Optional simple-controller profile: "+resultName(r));
  for(int i=0;i<2;i++){XrActionSpaceCreateInfo space{XR_TYPE_ACTION_SPACE_CREATE_INFO};space.action=grip;space.subactionPath=handPaths[i];space.poseInActionSpace.orientation.w=1;check(xrCreateActionSpace(session,&space,&gripSpaces[i]),"xrCreateActionSpace");}
  XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};attach.countActionSets=1;attach.actionSets=&actions;check(xrAttachSessionActionSets(session,&attach),"xrAttachSessionActionSets");
 }
 static GLuint shader(GLenum type,const char* source){GLuint s=glCreateShader(type);glShaderSource(s,1,&source,nullptr);glCompileShader(s);GLint ok;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);if(!ok){char log[1024];glGetShaderInfoLog(s,sizeof(log),nullptr,log);glDeleteShader(s);throw std::runtime_error(log);}return s;}
 void createGraphics(){
  const char* vs=R"(#version 300 es
layout(location=0) in vec3 position;
uniform mat4 mvp;void main(){gl_Position=mvp*vec4(position,1);gl_PointSize=6.;})";
  const char* fs=R"(#version 300 es
precision mediump float;uniform vec4 tint;out vec4 color;void main(){color=tint;})";
  GLuint v=shader(GL_VERTEX_SHADER,vs),f=shader(GL_FRAGMENT_SHADER,fs);program=glCreateProgram();glAttachShader(program,v);glAttachShader(program,f);glLinkProgram(program);glDeleteShader(v);glDeleteShader(f);GLint linked;glGetProgramiv(program,GL_LINK_STATUS,&linked);if(!linked)throw std::runtime_error("OpenXR scene shader link failed");
  glGenFramebuffers(1,&framebuffer);glGenVertexArrays(1,&vao);glGenBuffers(1,&buffer);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,buffer);glBufferData(GL_ARRAY_BUFFER,1024*sizeof(float),nullptr,GL_DYNAMIC_DRAW);glVertexAttribPointer(0,3,GL_FLOAT,GL_FALSE,0,nullptr);glEnableVertexAttribArray(0);
 }
 void events(){
  XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
  for(;;){XrResult r=xrPollEvent(instance,&event);if(r==XR_EVENT_UNAVAILABLE)break;check(r,"xrPollEvent");
   if(event.type==XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING){note("Runtime instance loss pending");stop=true;}
   if(event.type==XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED){auto* changed=reinterpret_cast<XrEventDataSessionStateChanged*>(&event);if(changed->session==session){state=changed->state;note("Session state: "+std::to_string(state));
    if(state==XR_SESSION_STATE_READY&&!running){XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};begin.primaryViewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;check(xrBeginSession(session,&begin),"xrBeginSession");running=true;}
    if(state==XR_SESSION_STATE_STOPPING&&running){check(xrEndSession(session),"xrEndSession");running=false;stop=true;}
    if(state==XR_SESSION_STATE_EXITING||state==XR_SESSION_STATE_LOSS_PENDING)stop=true;
   }}
   if(event.type==XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED){for(auto hand:handPaths){XrInteractionProfileState profile{XR_TYPE_INTERACTION_PROFILE_STATE};check(xrGetCurrentInteractionProfile(session,hand,&profile),"xrGetCurrentInteractionProfile");note("Interaction profile path: "+std::to_string(profile.interactionProfile));}}
   if(event.type==XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING)note("Reference space change pending; runtime owns relocalization");
   event={XR_TYPE_EVENT_DATA_BUFFER};
  }
 }
 static mr::Mat4 poseMatrix(const XrPosef& pose){float q[]={pose.orientation.x,pose.orientation.y,pose.orientation.z,pose.orientation.w};auto m=mr::Mat4::quaternion(q);m.m[12]=pose.position.x;m.m[13]=pose.position.y;m.m[14]=pose.position.z;return m;}
 static mr::Mat4 projection(const XrFovf& f){float l=std::tan(f.angleLeft),r=std::tan(f.angleRight),d=std::tan(f.angleDown),u=std::tan(f.angleUp);mr::Mat4 m;m.m[0]=2/(r-l);m.m[5]=2/(u-d);m.m[8]=(r+l)/(r-l);m.m[9]=(u+d)/(u-d);m.m[10]=-100.05f/99.95f;m.m[11]=-1;m.m[14]=-10.f/99.95f;return m;}
 std::vector<float> input(XrTime time){
  std::vector<float> points;points.reserve(162);validJoints=0;
  if(state==XR_SESSION_STATE_FOCUSED){
   XrActiveActionSet active{actions,XR_NULL_PATH};XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};sync.countActiveActionSets=1;sync.activeActionSets=&active;check(xrSyncActions(session,&sync),"xrSyncActions");
   for(int i=0;i<2;i++){
    XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};get.action=select;get.subactionPath=handPaths[i];XrActionStateBoolean selected{XR_TYPE_ACTION_STATE_BOOLEAN};check(xrGetActionStateBoolean(session,&get,&selected),"xrGetActionStateBoolean");
    if(selected.isActive&&selected.currentState&&selected.changedSinceLastSync){selects++;XrHapticActionInfo action{XR_TYPE_HAPTIC_ACTION_INFO};action.action=haptic;action.subactionPath=handPaths[i];XrHapticVibration vibration{XR_TYPE_HAPTIC_VIBRATION};vibration.amplitude=.15f;vibration.duration=30'000'000;vibration.frequency=XR_FREQUENCY_UNSPECIFIED;XrResult r=xrApplyHapticFeedback(session,&action,reinterpret_cast<XrHapticBaseHeader*>(&vibration));if(XR_FAILED(r))note("Optional haptic: "+resultName(r));}
    get.action=grip;XrActionStatePose gripState{XR_TYPE_ACTION_STATE_POSE};check(xrGetActionStatePose(session,&get,&gripState),"xrGetActionStatePose");
    if(gripState.isActive){XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};check(xrLocateSpace(gripSpaces[i],base,time,&location),"xrLocateSpace grip");if(location.locationFlags&XR_SPACE_LOCATION_POSITION_VALID_BIT){points.insert(points.end(),{location.pose.position.x,location.pose.position.y,location.pose.position.z});}}
   }
  }
  for(int i=0;i<2;i++)if(trackers[i]&&locateHand){
   std::array<XrHandJointLocationEXT,XR_HAND_JOINT_COUNT_EXT> joints{};
   XrHandJointLocationsEXT locations{XR_TYPE_HAND_JOINT_LOCATIONS_EXT};locations.jointCount=joints.size();locations.jointLocations=joints.data();
   XrHandJointsLocateInfoEXT locate{XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT};locate.baseSpace=base;locate.time=time;
   XrResult r=locateHand(trackers[i],&locate,&locations);if(XR_FAILED(r)){note("Hand backend disabled after error: "+resultName(r));destroyHand(trackers[i]);trackers[i]=XR_NULL_HANDLE;}if(XR_SUCCEEDED(r)&&locations.isActive)for(const auto& joint:joints)if(joint.locationFlags&XR_SPACE_LOCATION_POSITION_VALID_BIT){points.insert(points.end(),{joint.pose.position.x,joint.pose.position.y,joint.pose.position.z});validJoints++;}
  }
  return points;
 }
 void render(const XrView& view,const Swapchain& swap,uint32_t image,const std::vector<float>& points){
  glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,swap.images.at(image).image,0);
  if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw std::runtime_error("Runtime swapchain framebuffer incomplete");
  glViewport(0,0,swap.width,swap.height);glDisable(GL_DEPTH_TEST);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glClearColor(.02f,.03f,.06f,blend==XR_ENVIRONMENT_BLEND_MODE_OPAQUE?1.f:0.f);glClear(GL_COLOR_BUFFER_BIT);
  auto vp=projection(view.fov)*poseMatrix(view.pose).inverse();
  const float y=stage?1.4f:0;
  const float target[]={-.2f,y-.2f,-2,.2f,y-.2f,-2,-.2f,y+.2f,-2,-.2f,y+.2f,-2,.2f,y-.2f,-2,.2f,y+.2f,-2};
  glUseProgram(program);glBindVertexArray(vao);glBindBuffer(GL_ARRAY_BUFFER,buffer);glUniformMatrix4fv(glGetUniformLocation(program,"mvp"),1,GL_FALSE,vp.m);
  glUniform4f(glGetUniformLocation(program,"tint"),selects%2?.25f:.6f,selects%2?.9f:.3f,1,1);glBufferSubData(GL_ARRAY_BUFFER,0,sizeof(target),target);glDrawArrays(GL_TRIANGLES,0,6);
  if(!points.empty()){glUniform4f(glGetUniformLocation(program,"tint"),.3f,1,.7f,1);glBufferSubData(GL_ARRAY_BUFFER,0,points.size()*sizeof(float),points.data());glDrawArrays(GL_POINTS,0,points.size()/3);}
 }
 void frame(){
  XrFrameWaitInfo wait{XR_TYPE_FRAME_WAIT_INFO};XrFrameState frameState{XR_TYPE_FRAME_STATE};check(xrWaitFrame(session,&wait,&frameState),"xrWaitFrame");protocol.waited();
  XrFrameBeginInfo begin{XR_TYPE_FRAME_BEGIN_INFO};check(xrBeginFrame(session,&begin),"xrBeginFrame");protocol.begun();frames++;
  XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};layer.space=base;
  bool submit=false;
  try{
   if(frameState.shouldRender){
    XrViewLocateInfo locate{XR_TYPE_VIEW_LOCATE_INFO};locate.viewConfigurationType=XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;locate.displayTime=frameState.predictedDisplayTime;locate.space=base;
    XrViewState viewState{XR_TYPE_VIEW_STATE};uint32_t count=0;check(xrLocateViews(session,&locate,&viewState,views.size(),&count,views.data()),"xrLocateViews");
    const auto valid=XR_VIEW_STATE_POSITION_VALID_BIT|XR_VIEW_STATE_ORIENTATION_VALID_BIT;
    if(count==chains.size()&&(viewState.viewStateFlags&valid)==valid){
     const auto points=input(frameState.predictedDisplayTime);
     for(size_t i=0;i<chains.size();i++){
      auto& swap=chains[i];uint32_t index=0;XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};check(xrAcquireSwapchainImage(swap.handle,&acquire,&index),"xrAcquireSwapchainImage");protocol.acquired();
      XrSwapchainImageWaitInfo waitImage{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};waitImage.timeout=XR_INFINITE_DURATION;check(xrWaitSwapchainImage(swap.handle,&waitImage),"xrWaitSwapchainImage");protocol.imageReady();
      try{render(views[i],swap,index,points);}catch(...){XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};xrReleaseSwapchainImage(swap.handle,&release);throw;}
      XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};check(xrReleaseSwapchainImage(swap.handle,&release),"xrReleaseSwapchainImage");protocol.released();
      auto& p=projectionViews[i];p.pose=views[i].pose;p.fov=views[i].fov;p.subImage.swapchain=swap.handle;p.subImage.imageRect={{0,0},{swap.width,swap.height}};p.subImage.imageArrayIndex=0;
     }
     layer.viewCount=projectionViews.size();layer.views=projectionViews.data();submit=true;
    }
   }
  }catch(...){XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};end.displayTime=frameState.predictedDisplayTime;end.environmentBlendMode=blend;xrEndFrame(session,&end);protocol.aborted();throw;}
  const XrCompositionLayerBaseHeader* layers[]={reinterpret_cast<const XrCompositionLayerBaseHeader*>(&layer)};
  XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};end.displayTime=frameState.predictedDisplayTime;end.environmentBlendMode=blend;end.layerCount=submit?1:0;end.layers=submit?layers:nullptr;check(xrEndFrame(session,&end),"xrEndFrame");protocol.ended();if(submit)submitted++;
 }
 std::string run(JNIEnv* env,jobject activity){
  try{
   initialize(env,activity);const auto start=Clock::now();
   while(!stop){events();if(stop)break;if(running)frame();else{if(Clock::now()-start>std::chrono::seconds(15))throw std::runtime_error("Runtime did not transition to READY within 15s");std::this_thread::sleep_for(std::chrono::milliseconds(10));}}
   if(running){
    XrResult r=xrRequestExitSession(session);if(XR_FAILED(r))note("xrRequestExitSession: "+resultName(r));
    const auto deadline=Clock::now()+std::chrono::seconds(2);
    while(running&&Clock::now()<deadline){events();if(running)std::this_thread::sleep_for(std::chrono::milliseconds(10));}
    if(running)note("WARNING: runtime did not issue STOPPING before cleanup deadline");
   }
  }catch(const std::exception& e){note(std::string("ERROR: ")+e.what());}
  note("Frames waited: "+std::to_string(frames)+"; submitted: "+std::to_string(submitted)+"; selections: "+std::to_string(selects)+"; last valid hand joints: "+std::to_string(validJoints));
  note("No claim of runtime conformance or motion-to-photon latency. Monado must be installed/selected separately.");
  cleanup();return report.str();
 }
 void cleanup(){
  // Same render thread/current EGL context as creation. Safe even after partial initialization.
  if(context!=EGL_NO_CONTEXT){if(program)glDeleteProgram(program);if(framebuffer)glDeleteFramebuffers(1,&framebuffer);if(buffer)glDeleteBuffers(1,&buffer);if(vao)glDeleteVertexArrays(1,&vao);}
  for(auto h:trackers)if(h&&destroyHand)destroyHand(h);trackers={};
  for(auto space:gripSpaces)if(space)xrDestroySpace(space);gripSpaces={};
  if(head)xrDestroySpace(head);if(base)xrDestroySpace(base);head=base=XR_NULL_HANDLE;
  for(auto& c:chains)if(c.handle)xrDestroySwapchain(c.handle);chains.clear();
  if(session)xrDestroySession(session);session=XR_NULL_HANDLE;
  if(actions)xrDestroyActionSet(actions);actions=XR_NULL_HANDLE;
  if(instance)xrDestroyInstance(instance);instance=XR_NULL_HANDLE;
  if(display!=EGL_NO_DISPLAY){eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);eglTerminate(display);}
  display=EGL_NO_DISPLAY;context=EGL_NO_CONTEXT;surface=EGL_NO_SURFACE;
 }
};
}
extern "C" JNIEXPORT jlong JNICALL Java_dev_trackmr_openxr_SessionActivity_create(JNIEnv*,jobject){return reinterpret_cast<jlong>(new Session());}
extern "C" JNIEXPORT jstring JNICALL Java_dev_trackmr_openxr_SessionActivity_run(JNIEnv* env,jobject,jlong p,jobject activity){auto text=reinterpret_cast<Session*>(p)->run(env,activity);return env->NewStringUTF(text.c_str());}
extern "C" JNIEXPORT void JNICALL Java_dev_trackmr_openxr_SessionActivity_stop(JNIEnv*,jobject,jlong p){reinterpret_cast<Session*>(p)->stop=true;}
extern "C" JNIEXPORT void JNICALL Java_dev_trackmr_openxr_SessionActivity_destroy(JNIEnv*,jobject,jlong p){delete reinterpret_cast<Session*>(p);}
