"""Independent re-implementation of algorithm-spec.md written from the spec text alone (not from the Java
reference). Replays every conformance vector and the primitives; run from the repo root:
    python3 reference/spec-crosscheck/specpy.py [vectors/conformance/1.0.0]
It proves the spec is sufficient for a second implementation. Not part of any release artifact."""
import json, struct, math, sys, os
M=(1<<64)-1
class SM:
    def __init__(s,seed): s.st=seed&M
    def next(s):
        s.st=(s.st+0x9E3779B97F4A7C15)&M
        z=s.st
        z=((z^(z>>30))*0xBF58476D1CE4E5B9)&M
        z=((z^(z>>27))*0x94D049BB133111EB)&M
        return z^(z>>31)
    def nint(s,n):
        th=(1<<64)%n
        while True:
            r=s.next()
            if r>=th: return r%n
def lowmed(vals):
    order=sorted(range(len(vals)),key=lambda i:(vals[i],i))
    return vals[order[(len(vals)-1)//2]]
def kahan(xs):
    s=0.0;c=0.0
    for x in xs:
        y=x-c;t=s+y;c=(t-s)-y;s=t
    return s
def jacobi(A):
    A=[r[:] for r in A];V=[[1.0,0,0],[0,1.0,0],[0,0,1.0]]
    sw=0
    while sw<50:
        off=abs(A[0][1])+abs(A[0][2])+abs(A[1][2]);dg=abs(A[0][0])+abs(A[1][1])+abs(A[2][2])
        if off<=1e-12*dg: break
        for p,q in((0,1),(0,2),(1,2)):
            if A[p][q]==0: continue
            th=(A[q][q]-A[p][p])/(2*A[p][q])
            t=(1.0 if th>=0 else -1.0)/(abs(th)+math.sqrt(th*th+1))
            c=1/math.sqrt(t*t+1);s=t*c
            apq=A[p][q]
            A[p][p]=A[p][p]-t*apq;A[q][q]=A[q][q]+t*apq;A[p][q]=A[q][p]=0.0
            r=3-p-q;arp=A[r][p];arq=A[r][q]
            A[r][p]=A[p][r]=c*arp-s*arq;A[r][q]=A[q][r]=c*arq+s*arp
            for k in range(3):
                a=V[k][p];b=V[k][q];V[k][p]=c*a-s*b;V[k][q]=c*b+s*a
        sw+=1
    idx=sorted(range(3),key=lambda i:(-A[i][i],i))
    vecs=[]
    for i in idx:
        e=[V[0][i],V[1][i],V[2][i]];big=0
        for j in (1,2):
            if abs(e[j])>abs(e[big]): big=j
        if e[big]<0: e=[-x for x in e]
        vecs.append(e)
    return [A[i][i] for i in idx],vecs,sw
sz=lambda z:0.001+0.0015*z*z
def run(inp,d,conf):
    W,H=inp['width'],inp['height'];fx=inp['depthIntrinsics']['fx'];fy=inp['depthIntrinsics']['fy'];cx=inp['depthIntrinsics']['cx'];cy=inp['depthIntrinsics']['cy'];p=inp['poseCameraToWorldRowMajor']
    P=[]
    for v in range(H):
        for u in range(W):
            i=v*W+u;z=d[i];c=conf[i]
            if not(z>=0.1 and z<=5.0 and c>=0.5): continue
            xc=(u-cx)*z/fx;yc=-(v-cy)*z/fy;zc=-z
            P.append((p[0]*xc+p[1]*yc+p[2]*zc+p[3],p[4]*xc+p[5]*yc+p[6]*zc+p[7],p[8]*xc+p[9]*yc+p[10]*zc+p[11],z))
    n=len(P)
    if n<50: return dict(status='INSUFFICIENT_POINTS',v=n,i=0,o=0)
    ys=[q[1] for q in P];qy=sorted(ys)[(n-1)//5]
    rng=SM(inp['windowIndex']^0x9E3779B97F4A7C15);best=0;bc=0
    for _ in range(64):
        idx=rng.nint(n);c=ys[idx]
        if c>qy: continue
        k=sum(1 for q in P if abs(q[1]-c)<=5*sz(q[3]))
        if k>best: best=k;bc=c
    if best==0 or 10*best<3*n: return dict(status='NO_SUPPORT_PLANE',v=n,i=best,o=0)
    py=lowmed([q[1] for q in P if abs(q[1]-bc)<=5*sz(q[3])])
    pin=sum(1 for q in P if abs(q[1]-py)<=5*sz(q[3]))
    obj=[q for q in P if q[1]-py>5*sz(q[3])]
    if len(obj)<20: return dict(status='NO_OBJECT',v=n,i=pin,o=len(obj))
    hmax=max(q[1]-py for q in obj);top=[q for q in obj if q[1]-py>=hmax-0.02];m=len(top)
    mx=kahan([q[0] for q in top])/m;mz=kahan([q[2] for q in top])/m
    cxx=kahan([(q[0]-mx)*(q[0]-mx) for q in top])/m;cxz=kahan([(q[0]-mx)*(q[2]-mz) for q in top])/m;czz=kahan([(q[2]-mz)*(q[2]-mz) for q in top])/m
    vals,vecs,_=jacobi([[cxx,0,cxz],[0,0,0],[cxz,0,czz]])
    yaw=math.atan2(-vecs[0][2],vecs[0][0])
    if yaw>math.pi/2: yaw-=math.pi
    elif yaw<=-math.pi/2: yaw+=math.pi
    ux=math.cos(yaw);uz=-math.sin(yaw);wx=-uz;wz=ux
    ss=[q[0]*ux+q[2]*uz for q in top];tt=[q[0]*wx+q[2]*wz for q in top]
    lu=max(ss)-min(ss);lw=max(tt)-min(tt);sc=(min(ss)+max(ss))/2;tc=(min(tt)+max(tt))/2
    return dict(status='OK',v=n,i=pin,o=len(obj),py=py,center=[sc*ux+tc*wx,py+hmax/2,sc*uz+tc*wz],half=[lu/2,hmax/2,lw/2],yaw=yaw,L=max(lu,lw),Wd=min(lu,lw),Ht=hmax)
root=(sys.argv[1] if len(sys.argv)>1 else 'vectors/conformance/1.0.0').rstrip('/')+'/'
man=json.load(open(root+'manifest.json'));bad=0
for v in man['vectors']:
    if v['kind']!='scene': continue
    inp=json.load(open(root+v['input']));d=os.path.dirname(root+v['input'])+'/'
    n=inp['width']*inp['height']
    dep=struct.unpack('<%df'%n,open(d+inp['depthFile'],'rb').read())
    cf=inp['confidence']
    conf=struct.unpack('<%df'%n,open(d+cf['file'],'rb').read()) if 'file' in cf else [struct.unpack('<f',struct.pack('<f',cf['constant']))[0]]*n
    r=run(inp,dep,conf);e=json.load(open(root+v['expected']))
    ok=r['status']==e['status'] and (r['v'],r['i'],r['o'])==(e['counts']['validPixels'],e['counts']['supportPlaneInliers'],e['counts']['objectPoints'])
    mx=0
    if ok and r['status']=='OK':
        tol=v['tolerances']['dimensions.lengthM']['value']
        diffs=[r['L']-e['dimensions']['lengthM'],r['Wd']-e['dimensions']['widthM'],r['Ht']-e['dimensions']['heightM'],r['py']-e['supportPlaneY'],r['yaw']-e['obb']['yawRad']]+[a-b for a,b in zip(r['center'],e['obb']['centerWorld'])]+[a-b for a,b in zip(r['half'],e['obb']['halfExtentsM'])]
        mx=max(abs(x) for x in diffs);ok=mx<=tol/10
    print(v['id'],'OK' if ok else 'MISMATCH',r['status'],'maxdiff',mx)
    bad+=not ok
pr=json.load(open(root+'primitives.json'))
for s in pr['splitmix64']:
    g=SM(int(s['seed'],16));assert ['0x%016x'%g.next() for _ in range(8)]==s['first8']
for b in pr['boundedInt']:
    g=SM(7^0x9E3779B97F4A7C15);assert [g.nint(b['n']) for _ in range(16)]==b['first16'],b['n']
for m in pr['lowerMedian']: assert lowmed(m['values'])==m['value']
for k in pr['kahanSum']: assert kahan(k['values'])==k['sum']
for j in pr['jacobi3']:
    vals,vecs,sw=jacobi(j['matrix']);assert sw==j['sweeps'] and all(abs(a-b)<1e-9 for a,b in zip(vals,j['eigenvalues'])) and all(abs(a-b)<1e-9 for x,y in zip(vecs,j['eigenvectors']) for a,b in zip(x,y))
print('primitives OK; mismatches:',bad)
