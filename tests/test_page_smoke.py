"""DOM smoke tests: serve viewer/ and let headless Chrome render the page.

file:// will not work — fetch() of the JSON is blocked by CORS there.
"""
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

import pytest
from conftest import REPO

CHROME = Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")
VIEWER = REPO / "viewer"


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.fixture(scope="session")
def server():
    port = _free_port()
    proc = subprocess.Popen(
        [sys.executable, "-m", "http.server", str(port),
         "--directory", str(VIEWER), "--bind", "127.0.0.1"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    for _ in range(50):
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                break
        except OSError:
            time.sleep(0.1)
    else:
        proc.kill()
        pytest.fail("http.server did not come up")
    yield f"http://127.0.0.1:{port}"
    proc.terminate()


@pytest.fixture(scope="session")
def dom(server, tmp_path_factory):
    """Rendered DOM of coverage.html after its fetches settle."""
    if not CHROME.exists():
        pytest.skip("Chrome not installed at the expected path")
    profile = tmp_path_factory.mktemp("chrome")
    out = subprocess.run(
        [str(CHROME), "--headless", "--disable-gpu", "--no-sandbox",
         f"--user-data-dir={profile}", "--virtual-time-budget=6000",
         "--dump-dom", f"{server}/coverage.html"],
        capture_output=True, text=True, timeout=90,
    )
    return out.stdout


def test_page_renders_all_five_tabs(dom):
    for tab in ("tab-map", "tab-tree", "tab-gap", "tab-audit", "tab-field"):
        assert f'id="{tab}"' in dom, tab


def test_map_reports_the_measured_counts(dom):
    text = re.sub(r"<[^>]+>", " ", dom)
    assert "36" in text and "31" in text and "18" in text


def test_no_fatal_load_error(dom):
    """The fatal div stays hidden and empty when all fetches succeed."""
    m = re.search(r'<div id="fatal"([^>]*)>(.*?)</div>', dom, re.S)
    assert m, "fatal div missing from the page"
    assert "hidden" in m.group(1), f"fatal banner is showing: {m.group(2)[:200]}"
    assert m.group(2).strip() == ""
