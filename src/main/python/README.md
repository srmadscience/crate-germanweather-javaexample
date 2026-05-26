# QueryCrate — Python

Python port of the CrateDB climate data load-generator.

## Prerequisites

- Python 3.10+

## Setup

```bash
pip install -r requirements.txt
```

## Usage

```bash
CRATE_USER=<user> CRATE_PASSWORD=<password> \
python query_crate.py <duration-seconds> <host> <requests-per-second> <sslmode> [TYPE:COUNT ...]
```

### Examples

```bash
# WKT queries only, indefinite mode (runs for 120 seconds)
CRATE_USER=admin CRATE_PASSWORD=secret python query_crate.py 120 myhost 50 disable

# Mixed workload
CRATE_USER=admin CRATE_PASSWORD=secret python query_crate.py 120 myhost 50 disable WKT:100 REGION:50 FTS:30
```
