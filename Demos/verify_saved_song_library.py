"""Real Java/MySQL acceptance test. Run only against a disposable local stack.

Creates two guests and one test-only account; never calls a model or deletes
existing data. Requires the repository's seeded recordings. Optional --fixture
writes temporary browser-login credentials (keep outside the repository).
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import http.cookiejar
import json
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base', default='http://127.0.0.1:8080')
parser.add_argument('--fixture')
args = parser.parse_args()
assert urllib.parse.urlparse(args.base).hostname in ('127.0.0.1', 'localhost'), 'Disposable local stack only'
checks = []
def check(name, condition):
    assert condition, name
    checks.append(name)

class Client:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        self.call('GET', '/auth/session')
    def call(self, method, path, body=None, expected=200, csrf=True):
        headers = {'Content-Type':'application/json', 'Idempotency-Key':str(uuid.uuid4())}
        if csrf:
            headers.update({'X-XSRF-TOKEN':urllib.parse.unquote(c.value) for c in self.cookies if c.name=='XSRF-TOKEN'})
        request = urllib.request.Request(args.base+'/api/v1'+path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            response = self.http.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        raw = response.read()
        assert response.code == expected, (method, path, response.code, raw[:300])
        return json.loads(raw) if raw else None
    def card(self, ids):
        conversation = self.call('POST','/conversations',expected=201)['id']
        payload = {'title':'音乐库验收样本','summary':'本卡为隔离环境的界面测试数据，不代表模型推荐。','songs':[{'recordingId':song,'title':titles.get(song,'不存在的录音'),'artistName':'张悬／安溥','reason':'验收用推荐理由快照','searchUrl':'https://music.163.com/'} for song in ids]}
        card = self.call('POST',f'/conversations/{conversation}/cards',{'type':'RECOMMENDATION_CARD','cardType':'MUSIC_RECOMMENDATIONS','payloadJson':json.dumps(payload,ensure_ascii=False)})
        return conversation,card['id']
    def save(self, song, card, expected=200):
        return self.call('PUT',f'/me/saved-songs/{song}',{'sourceMessageId':card},expected)

one='10000000-0000-4000-8000-000000000001'
two='10000000-0000-4000-8000-000000000002'
titles={one:'Scream',two:'宝贝（in the night）'}
a,b=Client(),Client()
conversation,card=a.card([one,two])
check('empty library and empty status',a.call('GET','/me/saved-songs')['items']==[] and a.call('GET','/me/saved-songs/status')==[])
a.call('PUT',f'/me/saved-songs/{one}',{'sourceMessageId':card},403,csrf=False)
check('CSRF is required',True)
first=a.save(one,card)
check('reason and provenance come from stored source',first['reason']=='验收用推荐理由快照' and first['sourceMessageId']==card and first['sourceAvailable'])
with ThreadPoolExecutor(max_workers=4) as pool:
    repeated=list(pool.map(lambda _:a.save(one,card),range(4)))
check('concurrent saves are idempotent',all(item['savedAt']==first['savedAt'] for item in repeated) and len(a.call('GET','/me/saved-songs')['items'])==1)
a.save(two,card)
page=a.call('GET','/me/saved-songs?limit=1')
nextpage=a.call('GET','/me/saved-songs?limit=1&cursor='+urllib.parse.quote(page['nextCursor']))
check('stable keyset pagination without duplicates',len(page['items'])==len(nextpage['items'])==1 and page['items'][0]['recordingId']!=nextpage['items'][0]['recordingId'] and nextpage['nextCursor'] is None)
check('search matches title',len(a.call('GET','/me/saved-songs?q=Scream')['items'])==1)
check('search escapes SQL wildcard characters',a.call('GET','/me/saved-songs?q=%25')['items']==[] and a.call('GET','/me/saved-songs?q=_')['items']==[])
for query in ('cursor=invalid','limit=0','limit=51','q='+'x'*121): a.call('GET','/me/saved-songs?'+query,expected=400)
check('invalid pagination and search bounds rejected',True)
check('status is owner scoped',len(a.call('GET',f'/me/saved-songs/status?recordingIds={one},{two}'))==2 and b.call('GET',f'/me/saved-songs/status?recordingIds={one},{two}')==[])
b.save(one,card,404)
b.call('GET',f'/me/saved-songs/{one}/context',expected=404)
check('foreign source and context inaccessible',True)
missing=str(uuid.uuid4())
a.save(missing,card,404)
_,fake=a.card([missing])
a.save(missing,fake,404)
check('song must exist in both source and canonical catalog',True)
a.call('PUT',f'/me/saved-songs/{one}',{},400)
a.call('GET','/me/saved-songs/status?recordingIds='+','.join([one]*101),expected=400)
check('invalid source body and oversized status batch rejected',True)
context=a.call('GET',f'/me/saved-songs/{one}/context')['content']
check('chat draft contains readable canonical identity',first['title'] in context and first['artistName'] in context and one not in context)
a.call('DELETE','/conversations/'+conversation,expected=204)
retained=a.call('GET','/me/saved-songs')['items']
check('deleted source retains songs and snapshots',len(retained)==2 and all(not song['sourceAvailable'] and song['reason']=='验收用推荐理由快照' for song in retained))
for _ in range(2): a.call('DELETE',f'/me/saved-songs/{two}',expected=204)
check('remove is idempotent and other song remains',len(a.call('GET','/me/saved-songs')['items'])==1)
a.call('GET',f'/me/saved-songs/{two}/context',expected=404)
email='library-'+uuid.uuid4().hex[:12]+'@example.invalid'
password='LibraryQA-'+uuid.uuid4().hex
account=a.call('POST','/auth/register',{'email':email,'password':password,'displayName':'音乐库验收'},201)
check('guest saves survive registration',account['authenticated'] and len(a.call('GET','/me/saved-songs')['items'])==1)
otherconversation,othercard=b.card([one,two])
b.save(one,othercard);b.save(two,othercard)
b.call('POST','/auth/login',{'email':email,'password':password})
merged=b.call('GET','/me/saved-songs')['items']
check('login merges guest library without duplicate songs',len(merged)==2 and len({item['recordingId'] for item in merged})==2)
check('target provenance wins duplicate merge',next(item for item in merged if item['recordingId']==one)['sourceMessageId']==card)
check('migrated unique song source remains accessible',next(item for item in merged if item['recordingId']==two)['sourceAvailable'])
a.call('DELETE',f'/me/saved-songs/{one}',expected=204)
check('second device sees shared account changes',len(b.call('GET','/me/saved-songs')['items'])==1)
b.call('DELETE',f'/me/saved-songs/{two}',expected=204)
b.call('POST','/auth/logout')
b.call('GET','/auth/session')
check('logout does not expose account library',b.call('GET','/me/saved-songs')['items']==[])
if args.fixture:
    fixture=Path(args.fixture)
    fixture.write_text(json.dumps({'email':email,'password':password,'conversationId':otherconversation,'cardId':othercard,'songs':[one,two]}))
    fixture.chmod(0o600)
print(json.dumps({'passed':len(checks),'checks':checks},ensure_ascii=False,indent=2))
