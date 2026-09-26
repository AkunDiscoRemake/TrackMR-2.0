package dev.trackmr.handtracking

/** Precomputed plane offsets: no floating-point rotation/division in the pixel loop. */
class YuvSamplingPlan(
    val left: Int,val top: Int,val sourceWidth: Int,val sourceHeight: Int,
    val targetWidth: Int,val rotation: Int,
    val yStride: Int,val yPixel: Int,val uStride: Int,val uPixel: Int,val vStride: Int,val vPixel: Int
) {
    private val swapped=rotation%180!=0
    private val scaledWidth=minOf(targetWidth,sourceWidth)
    private val scaledHeight=(sourceHeight.toLong()*scaledWidth/sourceWidth).toInt().coerceAtLeast(1)
    val width=if(swapped)scaledHeight else scaledWidth
    val height=if(swapped)scaledWidth else scaledHeight
    val yColumns=IntArray(width);val uColumns=IntArray(width);val vColumns=IntArray(width)
    val yRows=IntArray(height);val uRows=IntArray(height);val vRows=IntArray(height)
    init {
        require(sourceWidth>0&&sourceHeight>0&&targetWidth>0&&rotation in listOf(0,90,180,270))
        fun sample(index: Int,count: Int,size: Int,reverse: Boolean): Int {
            val i=if(reverse)count-1-index else index
            return (((2L*i+1)*size)/(2L*count)).toInt().coerceIn(0,size-1)
        }
        for(x in 0 until width){
            val source=if(swapped)top+sample(x,width,sourceHeight,rotation==90) else left+sample(x,width,sourceWidth,rotation==180)
            yColumns[x]=source*if(swapped)yStride else yPixel
            uColumns[x]=(source/2)*if(swapped)uStride else uPixel
            vColumns[x]=(source/2)*if(swapped)vStride else vPixel
        }
        for(y in 0 until height){
            val source=if(swapped)left+sample(y,height,sourceWidth,rotation==270) else top+sample(y,height,sourceHeight,rotation==180)
            yRows[y]=source*if(swapped)yPixel else yStride
            uRows[y]=(source/2)*if(swapped)uPixel else uStride
            vRows[y]=(source/2)*if(swapped)vPixel else vStride
        }
    }
}
