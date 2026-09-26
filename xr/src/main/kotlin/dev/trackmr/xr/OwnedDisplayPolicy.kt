package dev.trackmr.xr

/** Shell privileges never authorize operations on the phone's main or another app's display. */
object OwnedDisplayPolicy {
    fun allowed(id: Int,name: String,ownerUid: Int,callerUid: Int,ownerPackage: String)=
        id>0 && name.startsWith("TrackMR-app-") && callerUid==ownerUid && ownerPackage=="dev.trackmr"
    fun component(value: String)=value.length<300&&value.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+"))
}
