"""Package the reduced Android arm64 core and JNI with Microsoft's matching Java classes."""
import hashlib
from pathlib import Path
import sys
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

VERSION = "1.22.0"
AAR_SHA = "04a4617a9c797cf49225595e45b5546081cb34c86ac817581141577d3b7dbfe2"
stock, native = map(Path, sys.argv[1:])
if hashlib.sha256(stock.read_bytes()).hexdigest() != AAR_SHA:
    raise SystemExit("Unexpected stock Maven AAR")
output = Path(__file__).resolve().parents[2] / f"app/libs/onnxruntime-paddle-arm64-{VERSION}.aar"
output.parent.mkdir(exist_ok=True)
with ZipFile(stock) as src, ZipFile(output, "w") as out:
    for name in sorted(src.namelist()):
        if name.endswith("/") or name.startswith("headers/"):
            continue
        if name.startswith("jni/") and not name.startswith("jni/arm64-v8a/"):
            continue
        data = (native / Path(name).name).read_bytes() if name.startswith("jni/") else src.read(name)
        out.writestr(ZipInfo(name), data, compress_type=ZIP_DEFLATED, compresslevel=9)
print(output.name, hashlib.sha256(output.read_bytes()).hexdigest())
