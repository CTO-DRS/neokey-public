import re, collections, math
AR=re.compile(r'^[\u0621-\u063A\u0641-\u064A]{2,20}$')
TASH=re.compile(r'[\u064B-\u0652\u0640]')
def load_hd(p):
    c={}
    for line in open(p,encoding='utf-8'):
        parts=line.rstrip('\n').split(' ')
        if len(parts)!=2: continue
        w=TASH.sub('',parts[0])
        if AR.match(w): c[w]=c.get(w,0)+int(parts[1])
    return c
def load_lz(p):
    c={}; ids={}
    for line in open(p,encoding='utf-8'):
        parts=line.rstrip('\n').split('\t')
        if len(parts)<3: continue
        ids[parts[0]]=parts[1]
        w=TASH.sub('',parts[1])
        if AR.match(w): c[w]=c.get(w,0)+int(parts[2])
    return c
subs=load_hd('ar_full.txt')
news=load_lz('ara_news_2020_300K/ara_news_2020_300K-words.txt')
wiki=load_lz('ara_wikipedia_2021_300K/ara_wikipedia_2021_300K-words.txt')
tot=[sum(x.values()) for x in (subs,news,wiki)]
print(len(subs),len(news),len(wiki),tot)
allw=set(subs)|set(news)|set(wiki)
sc={}
for w in allw:
    a=subs.get(w,0);b=news.get(w,0);c=wiki.get(w,0)
    if a<5 and b<3 and c<3: continue
    sc[w]=0.55*a/tot[0]+0.25*b/tot[1]+0.20*c/tot[2]
top=sorted(sc,key=lambda w:-sc[w])[:150000]
open('ar_new.txt','w',encoding='utf-8').write('\n'.join(top)+'\n')
print(len(top), top[:30], top[-10:])
