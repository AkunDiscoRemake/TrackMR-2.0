package dev.trackmr.xr
import org.junit.Test
import org.junit.Assert.*
class SpatialTest {
 @Test fun startupNeverFakesMr(){ val s=ExperienceState();assertEquals(Experience.MR,s.requested);assertEquals(Experience.SPATIAL_SAFE,s.active);s.camera(CameraState.ACTIVE);assertEquals(Experience.MR,s.active);s.camera(CameraState.ERROR);assertEquals(Experience.SPATIAL_SAFE,s.active);s.request(Experience.VR);assertEquals(Experience.VR,s.active) }
 @Test fun hoverIsFrameRateIndependent(){ val a=HoverAnimation();val b=HoverAnimation();repeat(60){a.update(true,1/60f)};repeat(120){b.update(true,1/120f)};assertEquals(a.amount,b.amount,.001f);assertEquals(0f,a.update(false,.01f,true),0f) }
 @Test fun windowsRespectPinsAndBounds(){ val m=WindowManager(2);val a=m.spawn(WindowKind.HOME);a.pinned=true;m.spawn(WindowKind.STORE);m.spawn(WindowKind.SYSTEM);assertTrue(m.windows.contains(a));a.resize(100f);assertEquals(2.8f,a.width,0f);m.minimize(a.id);assertTrue(a.minimized);m.spawn(WindowKind.HOME);assertFalse(a.minimized);m.close(a.id);assertFalse(m.windows.contains(a)) }
 @Test fun clockRejectsUnknownDomain(){ val c=ClockDomain();assertNull(c.ageMs(100,200,false));assertNull(c.ageMs(300,200,true));assertEquals(10f,c.ageMs(10000000,20000000,true)!!,.001f) }
 @Test fun heatOverridesImmediately(){ val s=QualityScaler();assertFalse(s.update(16f,16.7f,4,90,QualityMode.QUALITY).handsAllowed);repeat(500){s.update(10f,16.7f,0,80,QualityMode.BALANCED)};assertTrue(s.quality.handsAllowed);assertTrue(s.quality.renderScale<=.95f) }
 @Test fun notificationsBounded(){ val n=LocalNotifications(3);repeat(20){n.add(it.toLong(),"error $it")};assertEquals(3,n.snapshot().size) }
 @Test fun recovery(){ assertTrue(RecoveryPolicy(2).safeMode);assertFalse(RecoveryPolicy(2).restoreLayout);assertFalse(RecoveryPolicy(0).safeMode) }
 @Test fun maximizeIsReversible(){val w=SpatialWindow(1,WindowKind.HOME);val initial=w.width to w.height;repeat(100){w.toggleMaximize();w.toggleMaximize()};assertEquals(initial.first,w.width,0f);assertEquals(initial.second,w.height,0f)}
 @Test fun staleClockNotReportedAsLatency(){assertNull(ClockDomain().ageMs(1,5_000_000_000,true))}
 @Test fun fiveWindowsAndKeyboardFitNativeAndAtlasBudgets(){
     assertTrue(SpatialBudget.worstCaseTiles()<=SpatialBudget.ATLAS_TILES)
     assertTrue(SpatialBudget.worstCaseItems()<=SpatialBudget.PACKET_ITEMS)
 }
 @Test fun restoreIds(){ val m=WindowManager();m.restore(SpatialWindow(20,WindowKind.HOME));assertEquals(21,m.spawn(WindowKind.STORE).id) }
}
