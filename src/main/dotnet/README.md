# QueryCrate — .NET

C# / .NET port of the CrateDB climate data load-generator.

## Prerequisites

- .NET 8.0 SDK

## Setup

```bash
dotnet restore
```

## Usage

```bash
CRATE_USER=<user> CRATE_PASSWORD=<password> \
dotnet run -- <duration-seconds> <host> <requests-per-second> <sslmode> [TYPE:COUNT ...]
```

### Examples

```bash
# WKT queries only, indefinite mode (runs for 120 seconds)
CRATE_USER=admin CRATE_PASSWORD=secret dotnet run -- 120 myhost 50 Disable

# Mixed workload
CRATE_USER=admin CRATE_PASSWORD=secret dotnet run -- 120 myhost 50 Disable WKT:100 REGION:50 FTS:30
```
