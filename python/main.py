"""
cdd-sync-pro entry point.

Usage:
    python main.py           # Opens the Flet GUI (default)
    python main.py --cli     # Headless CLI mode — requires config.yaml
    python main.py --cli --dry-run

Exits 0 on success, 1 on error.
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path


def _run_cli(dry_run: bool) -> None:
    """Headless pipeline — Flet not required."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%H:%M:%S",
    )

    config_path = Path("config.yaml")
    if not config_path.exists():
        logging.error("config.yaml not found. Copy config.template.yaml and fill in your paths.")
        sys.exit(1)

    try:
        # Local import so sys.path setup in tests works correctly
        from config import SyncConfig
        from sync.pipeline import run_sync

        cfg = SyncConfig.load(config_path)
        if dry_run:
            cfg.dry_run = True

        run_sync(cfg)

    except ValueError as exc:
        logging.error("Config error: %s", exc)
        sys.exit(1)
    except Exception as exc:
        logging.error("Sync failed: %s", exc)
        sys.exit(1)


def main() -> None:
    parser = argparse.ArgumentParser(description="cdd-sync-pro: sync filesystem → Serato crates")
    parser.add_argument(
        "--cli",
        action="store_true",
        help="Run headless CLI mode (requires config.yaml). Default: open GUI.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Log actions but write nothing to disk")
    args = parser.parse_args()

    if args.cli:
        _run_cli(dry_run=args.dry_run)
    else:
        # Lazy import — Flet not required when using --cli
        import gui  # noqa: PLC0415
        gui.launch()


if __name__ == "__main__":
    main()
