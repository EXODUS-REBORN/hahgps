#!/usr/bin/env python3
"""Обёртка: публикация пререлиза hahgps на сервер devrelease.

Сам инструмент один для обоих плагинов и лежит в репозитории hahtojer:
docs/devrelease/publish.py (на сервере — /opt/devrelease/publish.py).

    python3 docs/devrelease/publish.py --project hahgps --jar target/hahgps-1.6.8.jar \
        --notes "что нового"
    python3 docs/devrelease/publish.py --project hahgps --list
"""
import os
import runpy
import sys

TARGETS = [
    os.environ.get("DEVREL_PUBLISH", ""),
    "/opt/devrelease/publish.py",
    os.path.expanduser("~/hahtojer/docs/devrelease/publish.py"),
]


def main():
    for target in TARGETS:
        if target and os.path.isfile(target):
            sys.argv[0] = target
            runpy.run_path(target, run_name="__main__")
            return
    sys.exit("не нашёл publish.py — проверь /opt/devrelease/publish.py или ~/hahtojer/docs/devrelease/publish.py")


if __name__ == "__main__":
    main()
