import json
import pytest
from jarvis_win.core.desktop import parse_plan
from jarvis_win.core.persona import system_prompt
from jarvis_win.core.tools import _open_application
from jarvis_win.core import vision, planner


@pytest.mark.parametrize('step', [
    {'action':'shell','args':{'command':'del *'}},
    {'action':'click','args':{'x':True,'y':10}},
    {'action':'click','args':{'x':-1,'y':10}},
    {'action':'click','args':{'x':1,'y':10,'approved':True}},
    {'action':'type','args':{'text':'hello\n'}},
    {'action':'scroll','args':{'amount':11}},
    {'action':'open','args':{'app':'powershell.exe'}},
])
def test_untrusted_plan_cannot_expand_capabilities(step):
    with pytest.raises(ValueError):
        parse_plan(json.dumps([step]))


def test_plan_limit_and_empty():
    for data in ([], [{'action':'inspect','args':{}}]*9):
        with pytest.raises(ValueError):
            parse_plan(json.dumps(data))


def test_valid_plan_is_only_data(monkeypatch):
    import subprocess
    monkeypatch.setattr(subprocess, 'Popen', lambda *a, **k: pytest.fail('executed'))
    steps = parse_plan('[{"action":"open","args":{"app":"notepad"}},{"action":"verify","args":{}}]')
    assert len(steps) == 2


def test_unknown_application_never_launches(monkeypatch):
    import os
    monkeypatch.setattr(os, 'startfile', lambda *a: pytest.fail('launched'), raising=False)
    assert not _open_application({'app':'evil.exe'}).ok


def test_persona_has_no_invented_private_profile():
    text = system_prompt('Ali', 'coding')
    assert 'بالمذكر' in text and 'بالمؤنث' in text and 'الجزائرية' in text
    assert json.loads(text[text.index('{'):]) == {'name':'Ali', 'context':'coding'}
    assert len(system_prompt('x'*1000, 'y'*5000)) < 3000


class Reply:
    status_code = 200
    def __init__(self, content):
        self.content = content
    def json(self):
        return {'choices':[{'message':{'content':self.content}}]}


def test_vision_only_sends_provided_image_to_fixed_endpoint(monkeypatch):
    calls = []
    def post(url, **kw):
        calls.append((url, kw))
        return Reply('وصف')
    monkeypatch.setattr(vision.httpx, 'post', post)
    assert vision.analyze('test-key', b'jpeg') == 'وصف'
    url, kw = calls[0]
    assert url == 'https://openrouter.ai/api/v1/chat/completions'
    assert kw['json']['model'] == 'openrouter/free'
    assert len(calls) == 1
    assert kw['json']['messages'][1]['content'][1]['image_url']['url'].endswith('anBlZw==')


def test_remote_error_does_not_leak_body_or_key(monkeypatch):
    class Error:
        status_code = 401
        def json(self):
            pytest.fail('must not read error body')
    monkeypatch.setattr(vision.httpx, 'post', lambda *a, **k: Error())
    with pytest.raises(RuntimeError) as exc:
        vision.analyze('SECRET', b'jpeg')
    assert 'SECRET' not in str(exc.value)


def test_planner_rejects_remote_shell_instructions(monkeypatch):
    monkeypatch.setattr(planner.httpx, 'post', lambda *a, **k: Reply('{"steps":[{"action":"shell","args":{}}]}'))
    with pytest.raises(RuntimeError):
        planner.propose('key', 'task', 'model')
