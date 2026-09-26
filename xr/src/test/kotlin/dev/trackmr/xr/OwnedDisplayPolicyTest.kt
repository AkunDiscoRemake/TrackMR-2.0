package dev.trackmr.xr
import org.junit.Test
import org.junit.Assert.*
class OwnedDisplayPolicyTest {
 @Test fun ownership(){
  assertTrue(OwnedDisplayPolicy.allowed(2,"TrackMR-app-12",10001,10001,"dev.trackmr"))
  assertFalse(OwnedDisplayPolicy.allowed(0,"TrackMR-app-12",10001,10001,"dev.trackmr"))
  assertFalse(OwnedDisplayPolicy.allowed(2,"TrackMR-app-12",10001,2000,"dev.trackmr"))
  assertFalse(OwnedDisplayPolicy.allowed(2,"Phone",10001,10001,"dev.trackmr"))
  assertFalse(OwnedDisplayPolicy.allowed(2,"TrackMR-app-12",10001,10001,"another.app"))
 }
 @Test fun components(){assertTrue(OwnedDisplayPolicy.component("com.example/.MainActivity"));assertFalse(OwnedDisplayPolicy.component("com.example/.A;reboot"));assertFalse(OwnedDisplayPolicy.component("--display 0"))}
}
