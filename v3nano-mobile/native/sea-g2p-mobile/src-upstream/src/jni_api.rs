//! JNI bindings (feature `jni`) for `com.vieneu.mobile.g2p.SeaG2pNative`.
//!
//! Thin layer over the C ABI in `capi.rs`: same handle, same error contract, so the
//! phoneme output is identical to the Python wheel. A failing call throws
//! `IllegalStateException` with the library's last-error message.

use std::ffi::{CStr, CString};

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

use crate::capi::{
    sea_g2p_close, sea_g2p_last_error, sea_g2p_normalize, sea_g2p_open, sea_g2p_phonemize,
    sea_g2p_string_free, SeaG2p,
};

fn last_error() -> String {
    let p = sea_g2p_last_error();
    if p.is_null() {
        "unknown error".to_string()
    } else {
        unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned()
    }
}

fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

fn read_string(env: &mut JNIEnv, s: &JString) -> Option<CString> {
    let text: String = match env.get_string(s) {
        Ok(v) => v.into(),
        Err(_) => {
            throw(env, "invalid Java string");
            return None;
        }
    };
    match CString::new(text) {
        Ok(c) => Some(c),
        Err(_) => {
            throw(env, "text contains a NUL character");
            None
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vieneu_mobile_g2p_SeaG2pNative_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    dict_path: JString,
) -> jlong {
    let Some(path) = read_string(&mut env, &dict_path) else { return 0 };
    let handle = unsafe { sea_g2p_open(path.as_ptr()) };
    if handle.is_null() {
        throw(&mut env, &format!("sea_g2p_open failed: {}", last_error()));
        return 0;
    }
    handle as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_vieneu_mobile_g2p_SeaG2pNative_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    unsafe { sea_g2p_close(handle as *mut SeaG2p) };
}

unsafe fn run(
    env: &mut JNIEnv,
    handle: jlong,
    text: &JString,
    punc_norm: jint,
    f: unsafe extern "C" fn(*const SeaG2p, *const std::ffi::c_char, i32) -> *mut std::ffi::c_char,
    what: &str,
) -> jstring {
    if handle == 0 {
        throw(env, &format!("{what}: handle is closed"));
        return std::ptr::null_mut();
    }
    let Some(input) = read_string(env, text) else { return std::ptr::null_mut() };
    let out = unsafe { f(handle as *const SeaG2p, input.as_ptr(), punc_norm) };
    if out.is_null() {
        throw(env, &format!("{what} failed: {}", last_error()));
        return std::ptr::null_mut();
    }
    let result = unsafe { CStr::from_ptr(out) }.to_string_lossy().into_owned();
    unsafe { sea_g2p_string_free(out) };
    match env.new_string(result) {
        Ok(s) => s.into_raw(),
        Err(_) => {
            throw(env, &format!("{what}: cannot allocate result string"));
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vieneu_mobile_g2p_SeaG2pNative_nativePuncNorm(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jstring {
    let Some(input) = read_string(&mut env, &text) else { return std::ptr::null_mut() };
    let out = unsafe { crate::capi::sea_g2p_punc_norm(input.as_ptr()) };
    if out.is_null() {
        throw(&mut env, &format!("punc_norm failed: {}", last_error()));
        return std::ptr::null_mut();
    }
    let result = unsafe { CStr::from_ptr(out) }.to_string_lossy().into_owned();
    unsafe { sea_g2p_string_free(out) };
    match env.new_string(result) {
        Ok(s) => s.into_raw(),
        Err(_) => {
            throw(&mut env, "punc_norm: cannot allocate result string");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vieneu_mobile_g2p_SeaG2pNative_nativePhonemize(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
    punc_norm: jint,
) -> jstring {
    unsafe { run(&mut env, handle, &text, punc_norm, sea_g2p_phonemize, "phonemize") }
}

#[no_mangle]
pub extern "system" fn Java_com_vieneu_mobile_g2p_SeaG2pNative_nativeNormalize(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
    punc_norm: jint,
) -> jstring {
    unsafe { run(&mut env, handle, &text, punc_norm, sea_g2p_normalize, "normalize") }
}
