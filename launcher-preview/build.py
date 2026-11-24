"""Builds the offline preview with the same local Android tools as RadioKIA FM."""
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
FM_ROOT = ROOT.parent / "fm-offline"
sys.path.insert(0, str(FM_ROOT))
spec = importlib.util.spec_from_file_location("build_radiokia", FM_ROOT / "build.py")
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
builder.ROOT = ROOT
builder.PACKAGE_NAME = "RadioKIA_Pulpit_Podglad"
builder.VERSION = "0.3.13"
builder.EXTRA_SOURCES = [
    FM_ROOT / "src" / "pl" / "radiokia" / "fm" / name for name in ("Config.java", "XtRadio.java", "TopwayFmEvents.java", "FmScale.java")
]
builder.build()
