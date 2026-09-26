#include <jni.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <cstring>
#include <string>
#include <vector>
extern "C" JNIEXPORT jstring JNICALL Java_dev_trackmr_runtime_RuntimeActivity_probe(JNIEnv* env,jobject,jobject activity) {
    JavaVM* vm=nullptr;env->GetJavaVM(&vm);
    auto text=[&](const std::string& s){return env->NewStringUTF(s.c_str());};
    PFN_xrInitializeLoaderKHR initialize=nullptr;
    XrResult result=xrGetInstanceProcAddr(XR_NULL_HANDLE,"xrInitializeLoaderKHR",reinterpret_cast<PFN_xrVoidFunction*>(&initialize));
    if(XR_FAILED(result)||!initialize)return text("Loader não oferece xrInitializeLoaderKHR: "+std::to_string(result));
    XrLoaderInitInfoAndroidKHR init{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    init.applicationVM=vm;init.applicationContext=activity;
    result=initialize(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&init));
    if(XR_FAILED(result))return text("Falha ao inicializar loader: "+std::to_string(result));
    uint32_t count=0;result=xrEnumerateInstanceExtensionProperties(nullptr,0,&count,nullptr);
    if(XR_FAILED(result))return text("Nenhum runtime carregável. xrEnumerateInstanceExtensionProperties = "+std::to_string(result));
    std::vector<XrExtensionProperties> properties(count,{XR_TYPE_EXTENSION_PROPERTIES});
    result=xrEnumerateInstanceExtensionProperties(nullptr,count,&count,properties.data());
    if(XR_FAILED(result))return text("Falha enumerando extensões: "+std::to_string(result));
    bool android=false;for(const auto& p:properties)if(std::strcmp(p.extensionName,XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME)==0)android=true;
    if(!android)return text("Runtime sem XR_KHR_android_create_instance. Extensões: "+std::to_string(count));
    const char* extensions[]={XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidInfo.applicationVM=vm;androidInfo.applicationActivity=activity;
    XrInstanceCreateInfo info{XR_TYPE_INSTANCE_CREATE_INFO};info.next=&androidInfo;
    std::strncpy(info.applicationInfo.applicationName,"TrackMR diagnostics",XR_MAX_APPLICATION_NAME_SIZE-1);
    info.applicationInfo.applicationVersion=1;info.applicationInfo.apiVersion=XR_MAKE_VERSION(1,0,0);
    info.enabledExtensionCount=1;info.enabledExtensionNames=extensions;
    XrInstance instance=XR_NULL_HANDLE;result=xrCreateInstance(&info,&instance);
    if(XR_FAILED(result))return text("Runtime descoberto, mas xrCreateInstance falhou: "+std::to_string(result));
    XrInstanceProperties details{XR_TYPE_INSTANCE_PROPERTIES};result=xrGetInstanceProperties(instance,&details);
    std::string report=XR_SUCCEEDED(result)?std::string("Instância OpenXR real: ")+details.runtimeName:"Instância criada; falha ao consultar propriedades";
    XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};systemInfo.formFactor=XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId system;result=xrGetSystem(instance,&systemInfo,&system);
    report+="\nxrGetSystem: "+std::to_string(result)+"\nExtensões: "+std::to_string(count);
    xrDestroyInstance(instance);
    return text(report+"\nTeste de descoberta/instância apenas. Não cria sessão, compositor ou swapchain.");
}
