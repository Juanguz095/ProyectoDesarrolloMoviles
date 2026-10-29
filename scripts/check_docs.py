"""Verify local document links and acceptance traceability."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
sources = [root / 'README.md', root / 'AGENTS.md', *sorted((root / 'docs').rglob('*.md'))]
for source in sources:
    for destination in re.findall(r'\]\(([^)]+)\)', source.read_text()):
        if '://' not in destination and not destination.startswith('#'):
            assert (source.parent / destination.split('#')[0]).exists(), f'{source.relative_to(root)}: missing {destination}'
plan = (root / 'docs/PLAN_MAESTRO.md').read_text()
tasks = re.findall(r'^\| (P\d+\.\d+) \|', plan, re.M)
assert len(tasks) == len(set(tasks)) == 42
progress = set(re.findall(r'P\d+\.\d+', (root / 'docs/AVANCE.md').read_text()))
assert set(tasks) == progress, 'Progress and plan task IDs differ'
criteria = set(re.findall(r'^\| (CA-\d+) \|', plan, re.M))
stories = (root / 'docs/historias.md').read_text()
assert criteria == set(re.findall(r'^\| (CA-\d+) \|', stories, re.M))
for refs in re.findall(r'^\| HU-\d+ \|.*$', stories, re.M):
    assert re.search(r'CA-\d+', refs), 'Story has no acceptance criteria'
print(f'Documentation valid: {len(tasks)} tasks, {len(criteria)} traced acceptance criteria, {len(sources)} documents.')
