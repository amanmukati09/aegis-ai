"""Track C heavy-ML models — install-to-activate.

These modules ship as stubs so the endpoints exist and self-describe, but the heavy
dependencies (torch, tigramite, etc.) are NOT in the core requirements — the 3.8 GB EC2
box can't run them. On a capable machine (the laptop) install the extra requirements and
the same endpoints light up automatically.

Each capability exposes an `available()` check (tries to import its heavy dep) and a
`describe()` for the status endpoint. See LAPTOP_SETUP.md.
"""
