"""Read BundleDrill's report with the official MCP Python SDK and compare its pages to disk."""

import argparse
import asyncio
import json
import os
from pathlib import Path
import shutil

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def call(client, name, **arguments):
    result = await client.call_tool(name, arguments)
    assert not result.isError, result.content
    assert json.loads(result.content[0].text) == result.structuredContent
    return result.structuredContent


async def compare_pages(client, tool, key, expected, **filters):
    # Both ends exercise offset handling without scanning a large report one row at a time.
    offsets = sorted({0, max(0, len(expected) - 1), len(expected)})
    for offset in offsets:
        page = await call(client, tool, offset=offset, limit=1, **filters)
        assert page[key] == expected[offset:offset + 1]
        assert page["total"] == len(expected) and page["offset"] == offset
        assert page["nextOffset"] == (offset + 1 if offset + 1 < len(expected) else None)


async def check(install_dir, report_path):
    before = report_path.read_bytes()
    report = json.loads(before)
    lib = install_dir / "lib"
    if not list(lib.glob("*.jar")):
        raise ValueError(f"Missing distribution JARs in {lib}")
    java_home = os.environ.get("JAVA_HOME")
    executable = "java.exe" if os.name == "nt" else "java"
    java = str(Path(java_home) / "bin" / executable) if java_home else shutil.which(executable)
    if not java:
        raise RuntimeError("A Java 21 runtime is required")
    server = StdioServerParameters(command=java, args=[
        "-cp", str(lib / "*"), "io.github.almeida96.bundledrill.MainKt", "mcp", "--report", str(report_path),
    ])
    async with stdio_client(server) as (reader, writer), ClientSession(reader, writer) as client:
        assert (await client.initialize()).serverInfo.name == "bundledrill"
        await client.send_ping()
        tools = (await client.list_tools()).tools
        assert {tool.name for tool in tools} == {"restore_summary", "list_restore_points", "list_bundles", "bundle_prerequisites"}
        assert all(t.annotations.readOnlyHint and not t.annotations.openWorldHint for t in tools)
        summary = await call(client, "restore_summary")
        assert summary["status"] == report["status"]
        assert summary["recovered"] == sum(p["state"] == "RECOVERED" for p in report["points"])
        await compare_pages(client, "list_restore_points", "points", report["points"])
        missing = [p for p in report["points"] if p["state"] == "MISSING"]
        await compare_pages(client, "list_restore_points", "points", missing, state="MISSING")
        rows = []
        for b in report["bundles"]:
            row = {k: b[k] for k in ("index", "name", "sha256", "bytes", "state", "appliedOrder")}
            rows.append(dict(row, missingCount=len(b["missing"])))
            deps = [{"commit": oid, "missing": oid in b["missing"]} for oid in b["prerequisites"]]
            await compare_pages(client, "bundle_prerequisites", "prerequisites", deps, index=b["index"])
        await compare_pages(client, "list_bundles", "bundles", rows)
        assert (await client.call_tool("restore_summary", {"path": "other.json"})).isError
        assert (await client.call_tool("bundle_prerequisites", {"index": 64})).isError
    assert report_path.read_bytes() == before
    print(f"{report['status']}: all four report tools matched the evidence file")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-dir", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    asyncio.run(asyncio.wait_for(check(args.install_dir.resolve(), args.report.resolve()), timeout=60))
