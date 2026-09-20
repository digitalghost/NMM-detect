import sys
from pathlib import Path

import uvicorn


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))


if __name__ == "__main__":
    uvicorn.run("backend.server:app", host="127.0.0.1", port=4173, reload=False)
