#!/usr/bin/env python3
"""Prepare the desktop shader translator and print the verified path for XLATE."""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile
import uuid

REPO = Path(__file__).resolve().parents[2]
TOOLS = REPO / 'dm/gfx/tools'
sys.path.insert(0, str(TOOLS))
import dmsh_chain
import tool_lock

DLLS = ('dmsh_xlate.dll', 'Ryujinx.Graphics.Shader.dll', 'Ryujinx.Common.dll')
SDK = '10.0.114'


def matches(directory, lock):
    return all((directory / name).is_file() and tool_lock.sha16(directory / name) == lock[name] for name in DLLS)


def verify(directory, lock):
    if not matches(directory, lock):
        raise RuntimeError('desktop translator DLLs do not match dmsh_chain.lock')
    command = tool_lock.xlate_command(str(directory / 'dmsh_xlate'))
    result = subprocess.run([*command, '--source-sha'], check=True, capture_output=True, text=True, timeout=60)
    if result.stdout.strip() != lock['xlate_source']:
        raise RuntimeError('desktop translator embedded source does not match dmsh_chain.lock')
    return directory / 'dmsh_xlate'


def resolve(source=None):
    lock_file = Path(dmsh_chain.LOCK)
    lock = dict(line.split('\t', 1) for line in lock_file.read_text().splitlines() if line and not line.startswith('#'))
    if dmsh_chain.xlate_source_sha() != lock['xlate_source']:
        raise RuntimeError('dmsh_xlate source does not match dmsh_chain.lock')
    bundled = TOOLS / 'dmsh_xlate_bin'
    if matches(bundled, lock):
        return verify(bundled, lock)
    cache = REPO / 'peanuts/.build/pc-translator' / (tool_lock.sha16(lock_file) + '-' + sys.platform + '-' + platform.machine())
    cache.mkdir(parents=True, exist_ok=True)
    for ready in sorted(cache.glob('ready-*')):
        if matches(ready, lock):
            return verify(ready, lock)

    # Every build uses private source and obj directories. Readers see a generation only after all four checks pass.
    with tempfile.TemporaryDirectory(dir=cache, prefix='build-') as tmp:
        root = Path(tmp)
        project_dir = Path('dm/gfx/tools/dmsh_xlate')
        for relative in (project_dir, Path('dm/third_party/ryujinx')):
            shutil.copytree(REPO / relative, root / relative, ignore=shutil.ignore_patterns('bin', 'obj'))
        (root / 'global.json').write_text(json.dumps({'sdk': {'version': SDK, 'rollForward': 'disable'}}))
        dotnet = os.environ.get('PEANUTS_DOTNET_SDK', 'dotnet')
        env = dict(os.environ, MSBuildEnableWorkloadResolver='false', DOTNET_CLI_TELEMETRY_OPTOUT='1', DOTNET_NOLOGO='1')
        version = subprocess.run([dotnet, '--version'], cwd=root, env=env, capture_output=True, text=True)
        if version.returncode or version.stdout.strip() != SDK:
            raise RuntimeError('install the .NET SDK ' + SDK + ' or set PEANUTS_DOTNET_SDK to its dotnet executable')
        project = str(project_dir / 'dmsh_xlate.csproj')
        common = ['-m:1', '-nr:false', '-p:UseSharedCompilation=false']
        source = source or os.environ.get('PEANUTS_NUGET_SOURCE')
        feed = ['--source', str(Path(source).resolve())] if source else []
        publish = root / 'publish'
        for args in (['restore', project, '-p:NuGetAudit=false', *feed], ['publish', project, '-c', 'Release', '--no-restore', '-o', str(publish)]):
            subprocess.run([dotnet, *args, *common], cwd=root, env=env, check=True, stdout=sys.stderr)
        verify(publish, lock)
        ready = cache / ('ready-' + uuid.uuid4().hex)
        publish.rename(ready)
        return ready / 'dmsh_xlate'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', help='offline NuGet package feed directory')
    try:
        print(resolve(parser.parse_args().source))
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit('[RED] desktop translator: ' + str(error))
