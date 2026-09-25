"""Private, offline backend owned by the native Mac application."""
import os
import sys
import site
from pathlib import Path

resources = Path(__file__).resolve().parent
site.addsitedir(str(resources / "site-packages"))
sys.path.insert(0, str(resources / "studio"))
os.environ.update({
    "HF_HUB_OFFLINE": "1", "TRANSFORMERS_OFFLINE": "1",
    "PYTORCH_ENABLE_MPS_FALLBACK": "1",
    "MPLCONFIGDIR": str(Path(os.environ["NMM_OUTPUT_ROOT"]).parent / "matplotlib"),
})

import socket
import threading
import time
import uvicorn

parent = os.getppid()

def watch_parent():
    while True:
        time.sleep(2)
        if os.getppid() != parent:
            os._exit(0)

threading.Thread(target=watch_parent, daemon=True).start()
sock = socket.socket()
sock.bind(("127.0.0.1", 0))
sock.listen(128)
sock.set_inheritable(True)
Path(sys.argv[1]).write_text(str(sock.getsockname()[1]))
uvicorn.run("backend.server:app", host="127.0.0.1", log_level="info", fd=sock.fileno())
