import re,subprocess,glob,sys,xml.etree.ElementTree as ET
MIN=int(sys.argv[1])
root=ET.parse('/tmp/api/android-35/data/api-versions.xml').getroot()
cls={}
for c in root.findall('class'):
    cs=int(c.get('since','1')); m={}
    for e in c:
        if e.tag in('method','field'): m[e.get('name')]=int(e.get('since',cs))
    ext=[x.get('name') for x in c.findall('extends')]+[x.get('name') for x in c.findall('implements')]
    cls[c.get('name')]=(cs,m,ext)
def look(cn,mem,seen=None):
    if cn not in cls: return None
    cs,m,ext=cls[cn]
    if mem in m: return max(cs,m[mem])
    for e in ext:
        r=look(e,mem)
        if r: return r
    return None
out={}
files=glob.glob('/workspace/projects/60d5c182-443b-4747-bd5d-4f4eca7e8fec/keyboard-app/build/classes/com/hark/neokey/*.class')
txt=subprocess.run(['javap','-c','-p']+files,capture_output=True,text=True).stdout
cur=None;meth=None
for line in txt.splitlines():
    mm=re.match(r'^(?:public |final |abstract |class |interface )*class (\S+)|^Compiled from',line)
    if line.startswith('Compiled from'): continue
    if re.match(r'^\S.*class ',line): cur=line.split('class ')[1].split()[0]; continue
    if re.match(r'^  \S.*\(.*\).*;$',line) or re.match(r'^  \S.*\);$',line): meth=line.strip()[:70]; continue
    r=re.search(r'// (Method|Field|InterfaceMethod) ((?:android|java)/[\w/$]+)\.("?<?\w+>?"?):(\S+)',line)
    if r:
        cn,mem,sig=r.group(2),r.group(3).strip('"'),r.group(4)
        key=mem+(sig if r.group(1)!='Field' else '')
        v=look(cn,key)
        if v is None and r.group(1)=='Field': v=look(cn,mem)
        if v and v>MIN: out.setdefault((cur,meth),set()).add(f"{cn.split('/')[-1]}.{mem} ({v})")
    r=re.search(r'// class ((?:android|java)/[\w/$]+)',line)
    if r:
        v=cls.get(r.group(1),(0,))[0]
        if v>MIN: out.setdefault((cur,meth),set()).add(f"class {r.group(1).split('/')[-1]} ({v})")
for (c,m),s in sorted(out.items()): print(c.split('.')[-1],'|',m,'|',', '.join(sorted(s)))
