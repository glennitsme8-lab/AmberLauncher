/*
 * Horizon Android: Copyright (c) 2026 Ludovic Marie and the Horizon contributors. MIT License.
 * Horizon: https://github.com/horizon-rust/horizon
 * Android port: https://github.com/Vasysik/horizon-android
 *
 * File ini disalin dari port Android Horizon tanpa perubahan perilaku, supaya mesin Horizon
 * (pustaka native libhorizon_android.so) bisa berjalan di dalam AmberLauncher. Nama paket
 * dev.horizon.android WAJIB tetap: pustaka native mengikat nama kelas ini lewat JNI.
 * Teks lisensi lengkap ada di assets/horizon/LICENSE.txt di dalam APK.
 */
package dev.horizon.android;

/** SAF result callback only. The native activity, UI and file operations stay Rust. */
public final class HorizonActivity extends android.app.NativeActivity {
    static { System.loadLibrary("horizon_android"); }
    private native void nativeDocumentResult(int request, int result, String uri);

    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 4101) return;
        nativeDocumentResult(request, result,
                data == null || data.getData() == null ? null : data.getData().toString());
    }
}
