package com.cseini.byd.karaoke

import android.content.Context
import android.content.ContextWrapper

/** BYD SDK 장치의 getInstance(Context)에 넘기는 컨텍스트 — SDK 안쪽 권한 확인을 통과시킨다(릴레이·오토전비와 같은 방식). */
class VehicleContextWrapper(base: Context) : ContextWrapper(base) {
    override fun checkCallingOrSelfPermission(permission: String): Int = 0
    override fun checkCallingPermission(permission: String): Int = 0
    override fun checkPermission(permission: String, pid: Int, uid: Int): Int = 0
    override fun checkSelfPermission(permission: String): Int = 0
    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
    override fun enforceCallingPermission(permission: String, message: String?) {}
    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}
}
