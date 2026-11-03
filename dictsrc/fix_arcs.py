"""Rewrite vector pathData so arc flags are space-separated (Android's PathParser can't read '0 01-5' style)."""
import re, sys, glob
num = re.compile(r'[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?')
def fix(d):
    out=[]; i=0; cmd=None; n=len(d); argi=0
    while i<n:
        c=d[i]
        if c.isalpha() and c not in 'eE':
            cmd=c; argi=0; out.append(c); i+=1; continue
        if c in ' ,\t\n': i+=1; continue
        if cmd in 'aA' and argi%7 in (3,4):
            out.append(c); i+=1; argi+=1; continue   # single-char flag
        m=num.match(d,i)
        if not m: out.append(c); i+=1; continue
        out.append(m.group()); i=m.end(); argi+=1
    s=''
    for t in out:
        if s and not s[-1].isalpha() and not t.isalpha(): s+=' '
        s+=t
    return s
changed=0
for f in glob.glob('res/drawable/*.xml'):
    x=open(f).read()
    def rep(m):
        return 'android:pathData="'+fix(m.group(1))+'"'
    y=re.sub(r'android:pathData="([^"]*)"',rep,x)
    if y!=x and re.search(r'[aA]',''.join(re.findall(r'android:pathData="([^"]*)"',x))):
        open(f,'w').write(y); changed+=1; print(f)
print(changed)
