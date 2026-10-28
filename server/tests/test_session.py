"""A hold's words and a picture's frames (session.py). Run: uv run python -m unittest discover tests"""

import json
import unittest

from croissant_server.history import History
from croissant_server.session import OLD_APP, Session

START = {"type": "start", "mode": "dictate", "rate": 16000, "enc": "pcm_s16le", "ch": 1}
P = {"type": "object", "properties": {}}


class FakeWs:
    remote_address = ("100.100.100.9", 1)

    def __init__(self):
        self.sent = []
        self.closed = False

    async def send(self, text):
        self.sent.append(json.loads(text))

    async def close(self):
        self.closed = True


class FakeAudio:
    def __init__(self, words):
        self.words = words

    def feed(self, data):
        return False

    async def finish(self):
        return self.words


class FakeStt:
    def __init__(self, words="Où est la gare ?"):
        self.words = words
        self.asked = []

    def new_session(self, language, entry, partials):
        self.asked.append((language, entry, partials))
        return FakeAudio(self.words)


class FakeGenerations:
    def __init__(self, fail=None):
        self.fail = fail
        self.started = []

    def start(self, prompt, source=None, job_id=None, entry_id=None):
        if self.fail:
            raise RuntimeError(self.fail)
        self.started.append((prompt, source, job_id, entry_id))
        return job_id or "g_000001"


def session(stt=None, generations=None):
    ws, stt = FakeWs(), stt or FakeStt()
    return Session(ws, stt=stt, history=History(None), capture_dir=None, generations=generations), ws, stt


class DictateTest(unittest.IsolatedAsyncioTestCase):
    async def test_the_words_then_an_empty_answer_done(self):
        s, ws, stt = session()
        self.assertFalse(await s._on_control(json.dumps({**START, "stt": "local:whisper", "partials": False})))
        self.assertEqual(stt.asked, [(None, "local:whisper", False)])
        self.assertTrue(await s._on_control(json.dumps({"type": "end"})))
        self.assertEqual(ws.sent, [{"type": "final", "text": "Où est la gare ?"}, {"type": "answer_done"}])

    async def test_from_is_the_language_whisper_is_told(self):
        s, ws, stt = session()
        self.assertFalse(await s._on_control(json.dumps({**START, "from": "fr"})))
        self.assertEqual(stt.asked, [("fr", None, None)])
        self.assertEqual(ws.sent, [])

    async def test_a_language_not_on_the_list_is_an_error(self):
        s, ws, stt = session()
        self.assertTrue(await s._on_control(json.dumps({**START, "from": "xx"})))
        self.assertEqual(ws.sent, [{"type": "error", "message": "unsupported language xx"}])
        self.assertEqual(stt.asked, [])

    async def test_no_speech_is_said(self):
        s, ws, _ = session(FakeStt(words=""))
        await s._on_control(json.dumps(START))
        await s._on_control(json.dumps({"type": "end"}))
        self.assertEqual(ws.sent, [{"type": "error", "message": "No speech detected"}])


class OldAppTest(unittest.IsolatedAsyncioTestCase):
    """What the server no longer does is answered at once with OLD_APP, nothing heard or run."""

    async def test_holds_that_asked_the_server(self):
        ask = {**START, "variant": "default", "system": "You are the voice assistant.",
               "tools": [{"name": "agenda_add", "description": "add", "parameters": P}]}
        del ask["mode"]
        for start in (ask, {**ask, "image": True}, {**START, "mode": "transform", "image": True},
                      {**START, "mode": "translate", "from": "en", "to": "fr"}):
            s, ws, stt = session()
            self.assertTrue(await s._on_control(json.dumps(start)), start)
            self.assertEqual(ws.sent, [{"type": "error", "message": OLD_APP}])
            self.assertEqual(stt.asked, [])

    async def test_an_apps_ask_and_the_model_test(self):
        for frame in ({"type": "ask", "text": "Capital of France?"},
                      {"type": "bench", "system": "x", "cases": [{"say": "hi", "expect": ""}]}):
            s, ws, _ = session()
            self.assertTrue(await s._on_control(json.dumps(frame)))
            self.assertEqual(ws.sent, [{"type": "error", "message": OLD_APP}])


class GenerateTest(unittest.IsolatedAsyncioTestCase):
    async def test_a_picture_by_the_devices_id_and_pick(self):
        g = FakeGenerations()
        s, ws, _ = session(generations=g)
        done = await s._on_control(json.dumps({"type": "generate", "id": "g_ab12cd", "prompt": " a red fox ",
                                               "text_to_image": "gemini:imagen-4"}))
        self.assertTrue(done)
        self.assertEqual(g.started, [("a red fox", None, "g_ab12cd", "gemini:imagen-4")])
        self.assertEqual(ws.sent, [{"type": "generation", "id": "g_ab12cd", "prompt": "a red fox"}])

    async def test_from_a_photo_that_follows(self):
        g = FakeGenerations()
        s, ws, _ = session(generations=g)
        self.assertFalse(await s._on_control(json.dumps({"type": "generate", "id": "g_ab12cd", "image": True,
                                                         "prompt": "an oil painting of a pegboard",
                                                         "image_to_image": "local:z-image"})))
        await s._on_binary(b"\xff\xd8jpeg")
        self.assertEqual(g.started, [("an oil painting of a pegboard", b"\xff\xd8jpeg", "g_ab12cd", "local:z-image")])
        self.assertEqual(ws.sent, [{"type": "generation", "id": "g_ab12cd",
                                    "prompt": "an oil painting of a pegboard", "source": "photo"}])
        self.assertTrue(ws.closed)

    async def test_what_cannot_be_drawn_is_said(self):
        s, ws, _ = session(generations=FakeGenerations(fail="no text to image model is configured"))
        await s._on_control(json.dumps({"type": "generate", "prompt": "a fox"}))
        self.assertEqual(ws.sent, [{"type": "error", "message": "no text to image model is configured"}])
        s, ws, _ = session()
        await s._on_control(json.dumps({"type": "generate", "prompt": "a fox"}))
        self.assertEqual(ws.sent, [{"type": "error", "message": "pictures are disabled on this server"}])
        s, ws, _ = session(generations=FakeGenerations())
        await s._on_control(json.dumps({"type": "generate", "prompt": "  "}))
        self.assertEqual(ws.sent, [{"type": "error", "message": "prompt is required"}])


if __name__ == "__main__":
    unittest.main()
