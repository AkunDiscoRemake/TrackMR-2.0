package dev.trackmr.handtracking
import org.junit.Test
import org.junit.Assert.*
class HandTest {
 private fun observation(time: Long,value: Float=.4f)=HandObservation(FloatArray(63){value},time,Side.LEFT,.9f)
 @Test fun invalidFramesRejected(){val f=TemporalHandPipeline();assertNotNull(f.update(observation(1)));assertNull(f.update(observation(1)));assertNull(f.update(observation(2,Float.NaN)))}
 @Test fun gapResets(){val f=TemporalHandPipeline();f.update(observation(1));val s=f.update(observation(1_000_000_000,.8f))!!;assertEquals(.8f,s.points[0],.001f);assertEquals(0f,s.velocity[0],0f)}
 @Test fun predictionBoundedAndExpires(){val f=TemporalHandPipeline();f.update(observation(1_000_000_000));assertTrue(f.predict(1_020_000_000)!!.predicted);assertNull(f.predict(1_200_000_000))}
 @Test fun predictionNeverTriggersGesture(){val g=GestureEngine();assertTrue(g.update(HandSample(FloatArray(63),FloatArray(63),FloatArray(63),1,Side.LEFT,1f,true)).isEmpty())}
 @Test fun lowConfidenceCannotSelect(){val g=GestureEngine();assertTrue(g.update(HandSample(FloatArray(63),FloatArray(63),FloatArray(63),1,Side.LEFT,.2f)).isEmpty())}
 @Test fun filterModesRemainFinite(){for(mode in FilterMode.entries){val f=TemporalHandPipeline(mode);repeat(100){val s=f.update(observation(1L+it*33_000_000L,.3f+it*.001f));assertTrue(s!!.points.all{it.isFinite()})}}}
 @Test fun pinchEdgesAndRelease(){val g=GestureEngine();val p=FloatArray(63);p[15]=.2f;p[51]=.6f;p[12]=.4f;p[24]=.41f
 fun sample(t: Long)=HandSample(p.copyOf(),FloatArray(63),FloatArray(63),t,Side.LEFT,1f)
 assertTrue(g.update(sample(1_000_000_000)).any{it.kind==GestureKind.PINCH});assertFalse(g.update(sample(1_033_000_000)).any{it.kind==GestureKind.PINCH});p[24]=.7f;assertTrue(g.update(sample(1_066_000_000)).any{it.kind==GestureKind.PINCH_RELEASE})}
 @Test fun identityDoesNotFollowListOrder(){val a=HandAssociation();val left=observation(1,.2f);val right=observation(1,.8f).copy(side=Side.RIGHT);val ids=a.associate(listOf(left,right));val rev=a.associate(listOf(right.copy(timestampNs=2),left.copy(timestampNs=2)));assertEquals(ids[0],rev[1]);assertEquals(ids[1],rev[0])}
}
