#![allow(non_snake_case)]

mod route;

use std::ffi::c_char;

use route_support::{direct_support, set_socket_protector, SocketProtector};

/// # Safety
/// The configuration must be a valid NUL-terminated string for the duration of this call.
#[no_mangle]
pub unsafe extern "C" fn direct_start(json: *const c_char) -> i32 {
    unsafe { direct_support::run_session(json, None, "UAC Direct", route::run) }
}

#[no_mangle]
pub extern "C" fn direct_stop() -> i32 {
    direct_support::stop()
}

#[no_mangle]
pub extern "C" fn direct_is_running() -> i32 {
    direct_support::is_running()
}

#[no_mangle]
pub extern "C" fn direct_is_ready() -> i32 {
    direct_support::is_ready()
}

#[no_mangle]
pub extern "C" fn direct_last_error() -> *const c_char {
    direct_support::last_error()
}

#[no_mangle]
pub extern "C" fn direct_last_log() -> *const c_char {
    direct_support::last_log()
}

#[no_mangle]
pub extern "C" fn direct_set_socket_protector(protector: Option<SocketProtector>) {
    set_socket_protector(protector);
}
