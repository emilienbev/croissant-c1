"""What `croissant atelier new` writes into a new app's folder, from the answers: croissant.json,
croissant.js and README.md (the templates in atelier_new/, a block for each feature, so any
choice of them is a working app), the QR code and a .gitignore.

In the templates, {{key}} is a value, and {{#flag}}...{{/flag}} is kept only when the flag is
on ({{^flag}} when it is off); a block's marker alone on its line takes the line with it.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

from . import paths, qr

TEMPLATE = Path(__file__).with_name('atelier_new')
USES = ('network', 'model', 'words')        # in croissant.json's order
QR_START, QR_END = '<!-- croissant:qr -->', '<!-- /croissant:qr -->'
GITIGNORE = '.DS_Store\n'


@dataclass
class Tool:
    name: str
    description: str
    example: str


@dataclass
class Answers:
    repo: str                       # the folder's and the GitHub repository's name
    name: str                       # croissant.json's: the header word, up to 24
    hue: str
    glyph: str | None
    owner: str | None = None
    description: str = ''
    uses: list[str] = field(default_factory=list)
    tool: Tool | None = None
    starter: bool = True            # the README tells of the starter's count; an app's own, not

    @property
    def slug(self) -> str | None:
        return f'{self.owner}/{self.repo}' if self.owner else None


def default_tool(name: str) -> Tool:
    return Tool('add', f'Add to the {name} count; a negative number takes away', f'add one to {name}')


def manifest(a: Answers) -> dict:
    m = {'croissant': 1, 'name': a.name, 'hue': a.hue.upper()}
    if a.glyph:
        m['glyph'] = a.glyph
    m['version'] = '1.0.0'
    m['uses'] = [u for u in USES if u in a.uses]
    if a.tool:
        m['tools'] = [{
            'name': a.tool.name,
            'description': a.tool.description,
            'parameters': {
                'type': 'object',
                'properties': {'n': {'type': 'integer', 'description': 'how many; one when not said'}},
            },
            'examples': [a.tool.example],
        }]
    return m


_LINES = re.compile(r'^\{\{([#^])(\w+)\}\}\n(.*?)^\{\{/\2\}\}\n', re.S | re.M)
_INLINE = re.compile(r'\{\{([#^])(\w+)\}\}(.*?)\{\{/\2\}\}', re.S)
_VALUE = re.compile(r'\{\{(\w+)\}\}')


def fill(text: str, values: dict[str, str], flags: dict[str, bool]) -> str:
    def block(m):
        on = flags.get(m.group(2), False) == (m.group(1) == '#')
        return fill(m.group(3), values, flags) if on else ''
    text = _INLINE.sub(block, _LINES.sub(block, text))
    return _VALUE.sub(lambda m: values[m.group(1)], text)


def _line(s: str) -> str:
    """One line, whatever was typed: a comment or a heading cannot be broken out of."""
    return ' '.join(s.split())


def _context(a: Answers) -> tuple[dict, dict]:
    values = {
        'name': _line(a.name), 'repo': a.repo, 'slug': a.slug or '', 'description': _line(a.description),
        'about': _line(a.description) or 'an Atelier app for the C1', 'croissant': paths.update_repo() or 'emilienbev/croissant-c1',
        'tool': a.tool.name if a.tool else '', 'tool_example': _line(a.tool.example) if a.tool else '',
        'qr': qr_block(a.slug),
    }
    flags = {u: u in a.uses for u in USES}
    flags.update(starter=a.starter, uses=bool(a.uses), tool=a.tool is not None, owner=bool(a.owner), description=bool(a.description.strip()))
    return values, flags


def script(a: Answers) -> str:
    return fill((TEMPLATE / 'croissant.js').read_text(), *_context(a))


def readme(a: Answers) -> str:
    return fill((TEMPLATE / 'README.md').read_text(), *_context(a))


def qr_block(slug: str | None) -> str:
    """The README's lines between the QR markers: the code and how to scan it, or, with no
    owner yet, how to make one."""
    if not slug:
        return '*No QR code yet: it needs the GitHub repository\'s owner (below).*'
    return (f'<p align="center">\n'
            f'  <img src="qr.svg" width="280" alt="QR code of {qr.link(slug)}">\n'
            f'</p>\n'
            f'<p align="center">\n'
            f'  On a C1: Atelier, <b>+</b>, then the scan key, pointed at the code.<br>\n'
            f'  Or type <code>{slug}</code> there.\n'
            f'</p>')


def set_qr(readme_text: str, slug: str | None) -> str:
    """[readme_text] with its QR block made again for [slug]; a README without the markers gets
    the block under its title (or at its top)."""
    start, end = readme_text.find(QR_START), readme_text.find(QR_END)
    block = QR_START + '\n' + qr_block(slug) + '\n' + QR_END
    if start >= 0 and end > start:
        return readme_text[:start] + block + readme_text[end + len(QR_END):]
    title = re.search(r'^# .*\n', readme_text, re.M)
    at = title.end() if title else 0
    rest = readme_text[at:].lstrip('\n')
    return readme_text[:at] + ('\n' if title else '') + block + '\n\n' + rest


def from_manifest(m: dict, repo: str, owner: str | None, description: str = '') -> Answers:
    """An existing app's answers, from its croissant.json: for its README made again."""
    tool = None
    if m.get('tools'):
        t = m['tools'][0]
        ex = (t.get('examples') or [''])[0]
        tool = Tool(t['name'], t.get('description', ''), ex.get('say', '') if isinstance(ex, dict) else ex)
    return Answers(repo=repo, name=m['name'], hue=m['hue'], glyph=m.get('glyph'), owner=owner,
                   description=description, uses=[u for u in USES if u in m.get('uses', [])], tool=tool, starter=False)


def about(readme_text: str) -> str:
    """A README's first paragraph of prose under its title, as one line: its description."""
    body = re.sub(re.escape(QR_START) + '.*?' + re.escape(QR_END), '', readme_text, flags=re.S)
    for para in re.split(r'\n\s*\n', body):
        p = para.strip()
        if p and not p.startswith(('#', '<', '!', '-', '*', '`', '|', '>')):
            return _line(p)
    return ''


def write(folder: Path, a: Answers) -> list[Path]:
    """The app's files into [folder] (made, or empty): the ones written, in order."""
    folder.mkdir(parents=True, exist_ok=True)
    out = []

    def put(name, text):
        (folder / name).write_text(text)
        out.append(folder / name)
    put('croissant.json', json.dumps(manifest(a), indent=2) + '\n')
    put('croissant.js', script(a))
    put('README.md', readme(a))
    put('.gitignore', GITIGNORE)
    if a.slug:
        out.extend(qr.write(folder, a.slug, a.hue, a.glyph))
    return out
