"""What the person at the terminal sees and answers.

Output through rich (steps, results, progress); questions through questionary (arrow
keys, space to tick, enter to take). With no terminal, or with --no-input, nothing is
asked: every answer comes from the flags, and anything missing is an error rather than a
guess.

What is happening is always on screen: a step in progress shows as a line with a spinner
(spinner), which the ✓ line of what it did then replaces, so the terminal is never silent
while something runs.
"""
import contextlib
import sys

import questionary
from rich.console import Console
from rich.live import Live
from rich.markup import escape
from rich.padding import Padding
from rich.panel import Panel
from rich.spinner import Spinner
from rich.text import Text
from rich.progress import BarColumn, DownloadColumn, Progress, TextColumn, TimeRemainingColumn, TransferSpeedColumn
from rich.table import Table

console = Console(highlight=False)
INTERACTIVE = sys.stdin.isatty() and sys.stdout.isatty()

# The R1's orange, and quiet greys, so the terminal reads like the device.
ORANGE, GREY, LINE = '#ff4f00', '#8e8a87', '#3a3a3d'
STYLE = questionary.Style([
    ('qmark', 'fg:#ff4f00 bold'), ('question', 'bold'), ('answer', 'fg:#ff4f00'),
    ('pointer', 'fg:#ff4f00 bold'), ('highlighted', 'fg:#ff4f00 bold'), ('selected', 'fg:#ff4f00'),
    ('instruction', 'fg:#8e8a87'), ('disabled', 'fg:#5a5856 italic'),
])


class Abort(Exception):
    """Stop the run with a message for the person, not a traceback."""


def hello(*lines: str):
    """The wordmark, as the boot logo draws it: the word in white, an orange dot after it."""
    console.print()
    console.print(f'  [bold]croissant[/][bold {ORANGE}].[/]')
    for line in lines:
        console.print(f'  [{GREY}]{escape(line)}[/]')


def step(title: str):
    console.print()
    console.rule(f'[bold]{escape(title)}', align='left', style=LINE)


def info(msg: str):
    console.print('  ' + escape(msg))


def ok(msg: str):
    console.print('  [green]✓[/] ' + escape(msg))


def warn(msg: str):
    console.print('  [yellow]![/] ' + escape(msg))


def link(url: str):
    """A URL on its own line, clickable where the terminal allows and plain to copy."""
    console.print(f'  [{ORANGE}][link={url}]{escape(url)}[/link][/]', soft_wrap=True)


def done(msg: str):
    """The last line of a run."""
    console.print()
    console.print(f'  [bold]Done[/][bold {ORANGE}].[/] {escape(msg)}')
    console.print()


def todo(msg: str):
    """Something the person does with their hands, on the R1 or the cable."""
    console.print(Panel(escape(msg), border_style=ORANGE, padding=(0, 1)))


def fail(msg: str):
    raise Abort(msg)


def banner(title: str, body: str):
    console.print(Panel(escape(body), title=f'[bold {ORANGE}]{escape(title)}', title_align='left',
                        border_style=LINE, padding=(1, 2)))


def summary(rows: list[tuple[str, str]]):
    t = Table(show_header=False, box=None, padding=(0, 2))
    t.add_column(style=GREY)
    t.add_column()
    for k, v in rows:
        t.add_row(escape(k), escape(v))
    console.print(t)


@contextlib.contextmanager
def progress(name: str, total: int):
    """A download bar; yields a function to call with each chunk's size."""
    cols = [TextColumn('  {task.description}'), BarColumn(complete_style=ORANGE),
            DownloadColumn(), TransferSpeedColumn(), TimeRemainingColumn()]
    with aside(), Progress(*cols, console=console, transient=True) as p:
        task = p.add_task(escape(name), total=total or None)
        yield lambda n: p.advance(task, n)


_live: Live | None = None     # the spinner's line, while one is on screen
_doing: list[str] = []        # what it says: the innermost spinner's, last


def _line():
    return Padding(Spinner('dots', text=Text(_doing[-1]), style=ORANGE), (0, 0, 0, 2))


@contextlib.contextmanager
def spinner(msg: str):
    """What is happening, on a line of its own behind a one-character spinner, for as long as
    the block runs; then the line goes, and the ✓ line printed next takes its place. Lines
    printed meanwhile (output, results) go above it. Inside another spinner it takes over the
    line and gives it back after. Without a terminal it is printed once, so a log still says
    what was going on."""
    global _live
    if not console.is_terminal:
        console.print(f'  [{GREY}]· {escape(msg)}[/]')
        yield
        return
    _doing.append(msg)
    outer = _live is None
    if outer:
        _live = Live(_line(), console=console, transient=True, refresh_per_second=12)
        _live.start()
    else:
        _live.update(_line())
    try:
        yield
    finally:
        _doing.pop()
        if outer:
            _live.stop()
            _live = None
        else:
            _live.update(_line())


@contextlib.contextmanager
def aside():
    """The spinner off the screen while something else needs the terminal's last lines: a
    question, a key to press, a download's bar. It comes back after."""
    if _live is None:
        yield
        return
    _live.stop()
    try:
        yield
    finally:
        _live.start()


def pause(msg: str = 'Press Enter when done.'):
    if INTERACTIVE:
        with aside():
            questionary.press_any_key_to_continue(msg, style=STYLE).ask()


# --- questions ------------------------------------------------------------------------

def _answer(q):
    with aside():
        a = q.ask()      # None when the person pressed Ctrl-C
    if a is None:
        raise KeyboardInterrupt
    return a


def select(message: str, choices: list, default=None):
    """choices: (title, value) or (title, value, reason it cannot be picked)."""
    qc = [questionary.Choice(c[0], value=c[1], disabled=c[2] if len(c) > 2 else None) for c in choices]
    pick = next((c for c in qc if c.value == default and not c.disabled), None)
    return _answer(questionary.select(message, qc, default=pick, style=STYLE,
                                      instruction='(arrows, enter)'))


def select_live(message: str, choices: list[tuple[str, object]], default=None, moved=None):
    """select, calling moved(value) whenever the highlight lands on a choice, the first one too: for a
    choice that is heard or seen as it is considered. Drawn as questionary draws select."""
    from prompt_toolkit.application import Application
    from prompt_toolkit.key_binding import KeyBindings
    from prompt_toolkit.layout import Layout, Window
    from prompt_toolkit.layout.controls import FormattedTextControl
    at = {'i': next((k for k, c in enumerate(choices) if c[1] == default), 0), 'done': False}

    def lines():
        head = [('class:qmark', '? '), ('class:question', message + ' ')]
        if at['done']:
            return head + [('class:answer', choices[at['i']][0])]
        out = head + [('class:instruction', '(arrows, enter)')]
        for k, (title, _) in enumerate(choices):
            here = k == at['i']
            out += [('', '\n'), ('class:pointer' if here else '', ' » ' if here else '   '),
                    ('class:highlighted' if here else '', title)]
        return out

    def move(by):
        at['i'] = (at['i'] + by) % len(choices)
        if moved:
            moved(choices[at['i']][1])

    keys = KeyBindings()
    keys.add('up')(lambda e: move(-1))
    keys.add('k')(lambda e: move(-1))
    keys.add('down')(lambda e: move(1))
    keys.add('j')(lambda e: move(1))

    @keys.add('enter')
    def _(e):
        at['done'] = True
        e.app.exit(result=choices[at['i']][1])

    @keys.add('c-c')
    def _(e):
        e.app.exit(exception=KeyboardInterrupt)

    app = Application(layout=Layout(Window(FormattedTextControl(lines, show_cursor=False))),
                      key_bindings=keys, style=STYLE)
    if moved:
        moved(choices[at['i']][1])
    with aside():
        return app.run()


def checkbox(message: str, choices: list[tuple[str, object, bool]]) -> list:
    qc = [questionary.Choice(t, value=v, checked=on) for t, v, on in choices]
    return _answer(questionary.checkbox(message, qc, style=STYLE, instruction='(space to tick, enter)'))


def text(message: str, default: str = '', validate=None) -> str:
    return _answer(questionary.text(message, default=default, validate=validate, style=STYLE))


def path(message: str, default: str = '') -> str:
    return _answer(questionary.path(message, default=default, style=STYLE)).strip()


def confirm(message: str, default=True) -> bool:
    return _answer(questionary.confirm(message, default=default, style=STYLE))


def confirm_erase(assume_yes: bool):
    """The one question before anything destructive. Everything on the R1 goes."""
    todo('This erases everything on the R1: rabbitOS, its data, your rabbit account on it.\n'
         'The way back is rabbit\'s flash tool: https://rabbit-hmi-oss.github.io/flashing/')
    if assume_yes:
        info('--yes given, going ahead.')
        return
    if not INTERACTIVE:
        fail('Nothing was changed: erasing needs --yes when nothing can be asked.')
    typed = text('Type erase to go ahead:', validate=lambda s: s in ('', 'erase') or 'type erase, or leave it empty to stop')
    if typed != 'erase':
        fail('Nothing was changed.')
