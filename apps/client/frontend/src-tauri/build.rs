fn main() {
    if std::env::var("PROFILE").as_deref() == Ok("release") && tauri_build::is_dev() {
        panic!("Production builds require custom-protocol; use the Tauri build command or --features custom-protocol.");
    }
    tauri_build::build()
}
