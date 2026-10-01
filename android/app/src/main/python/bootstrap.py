import os
import sys


def start_server(app_dir: str, host: str = "127.0.0.1", port: int = 8080, native_token: str = ""):
    app_dir = os.path.abspath(app_dir)
    src_dir = os.path.join(app_dir, "src")
    if src_dir not in sys.path:
        sys.path.insert(0, src_dir)
    if app_dir not in sys.path:
        sys.path.insert(0, app_dir)
    os.chdir(app_dir)
    os.environ["CWM_HOST"] = host
    os.environ["CWM_PORT"] = str(port)
    os.environ["CWM_NATIVE_TOKEN"] = str(native_token or "")

    os.makedirs(os.path.join(app_dir, "data"), exist_ok=True)
    os.makedirs(os.path.join(app_dir, "output"), exist_ok=True)

    import webapp  # noqa: E402

    webapp.app.run(host=host, port=port, threaded=True, use_reloader=False)
