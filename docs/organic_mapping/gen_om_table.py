from PIL import Image
import glob, os

# Direction label per glyph name (matches OrganicGlyphs.LABELS)
LABELS = {
 'straight':'STRAIGHT','left':'LEFT','right':'RIGHT',
 'slight_left':'SLIGHT_LEFT','slight_right':'SLIGHT_RIGHT',
 'sharp_left':'SHARP_LEFT','sharp_right':'SHARP_RIGHT',
 'uturn_left':'UTURN_LEFT','uturn_right':'UTURN_RIGHT',
 'arrive':'ARRIVE','exit_left':'RAMP_LEFT','exit_right':'RAMP_RIGHT',
 'roundabout':'GENERIC_ROUNDABOUT_RIGHT',
}
for n in range(1,9): LABELS['roundabout_exit_%d'%n]='GENERIC_ROUNDABOUT_RIGHT'

SIZE=48; ROW_BYTES=8; SIG_GRID=16; CELL=SIZE//SIG_GRID

def pack(path):
    im=Image.open(path).convert('RGBA').resize((SIZE,SIZE), Image.BILINEAR)
    px=im.load()
    bits=bytearray(ROW_BYTES*SIZE)
    for y in range(SIZE):
        for x in range(SIZE):
            r,g,b,a=px[x,y]
            if a>128 and (r+g+b)//3>128:
                bits[y*ROW_BYTES + (x>>3)] |= (1<<(x&7))
    return bits

def bit(p,x,y): return (p[y*ROW_BYTES + (x>>3)]>>(x&7))&1

def signature(p):
    out=bytearray(32)
    for gy in range(SIG_GRID):
        for gx in range(SIG_GRID):
            on=0
            for yy in range(CELL):
                for xx in range(CELL):
                    if bit(p, gx*CELL+xx, gy*CELL+yy): on+=1
            if on>=2:
                idx=gy*SIG_GRID+gx
                out[idx>>3]|=(1<<(idx&7))
    return out.hex()

order=['straight','left','right','slight_left','slight_right','sharp_left','sharp_right',
       'uturn_left','uturn_right','arrive','exit_left','exit_right','roundabout']+['roundabout_exit_%d'%n for n in range(1,9)]
for name in order:
    path=name+'.png'
    if not os.path.exists(path): continue
    sig=signature(pack(path))
    print('        e("%s", Direction.%s, "%s"),' % (sig, LABELS[name], name))
