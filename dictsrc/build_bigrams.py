import re, collections
TASH=re.compile(r'[\u064B-\u0652\u0640]')
TOK=re.compile(r'[\u0621-\u063A\u0641-\u064A\u064B-\u0652\u0640]+|[^\s\u0621-\u064A]')
vocab=open('ar_final.txt',encoding='utf-8').read().split('\n')
vs=set(vocab[:150000])
first=set(vocab[:40000])
big=collections.defaultdict(collections.Counter)
for f in ['ara_news_2020_300K/ara_news_2020_300K-sentences.txt','ara_wikipedia_2021_300K/ara_wikipedia_2021_300K-sentences.txt']:
    for line in open(f,encoding='utf-8'):
        s=line.split('\t',1)[-1]
        toks=[TASH.sub('',t) for t in TOK.findall(s)]
        for a,b in zip(toks,toks[1:]):
            if a in first and b in vs: big[a][b]+=1
# phrases boost
rank={w:i for i,w in enumerate(vocab)}
splice=collections.defaultdict(list)
for line in open('phrases.txt',encoding='utf-8'):
    ws=line.split()
    for a,b in zip(ws,ws[1:]):
        if rank.get(a,10**9)<300:
            if b not in splice[a]: splice[a].append(b)
        else: big[a][b]+=100000
out=[]
for a in vocab[:40000]+[l.split()[0] for l in open('phrases.txt',encoding='utf-8') if l.strip()]:
    if a not in big: continue
    c=big.pop(a)
    nx=[w for w,n in c.most_common(6) if n>=3]
    if a in splice:
        extra=[w for w in splice[a] if w not in nx[:2]][:2]
        nx=nx[:2]+extra+[w for w in nx[2:] if w not in extra]
        nx=nx[:6]
    if nx: out.append(a+'\t'+' '.join(nx))
for a,c in big.items():
    nx=[w for w,n in c.most_common(6) if n>=100000]
    if nx: out.append(a+'\t'+' '.join(nx))
open('bigram_ar_base.txt','w',encoding='utf-8').write('\n'.join(out)+'\n')
print(len(out))
for l in out[:15]: print(l)
