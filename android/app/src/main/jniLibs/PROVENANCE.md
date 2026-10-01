# Ciweimao 2.9.365 transport libraries

The ABI-specific `libcurl.so`, `libssl.so`, and `libcrypto.so` files are copied
unchanged from the user-supplied official Ciweimao 2.9.365 APK. Runtime code
pins their SHA-256 values before loading them. The application-owned
`libcwmtransport.so` JNI adapter is built from `src/main/cpp/`.
