"""Fixed, interpretable thermal features. Thermal regions are NOT anatomical segmentation."""
import numpy as np
from scipy.ndimage import gaussian_filter, label

DELTA_LEVELS=(1.,2.,3.,4.,6.,8.)
ABS_LEVELS=(24.,26.,28.,30.)

def region_features(t,d):
    # Features have explicit fixed units/scales before learned fold-local normalization.
    f=[];names=[]
    def add(name,value):names.append(name);f.append(float(value))
    for name,a,offset,scale in [('t',t,20.,15.),('delta',d,0.,10.)]:
        for q in [.1,.5,.8,.9,.95,.99]:add(f'{name}_q{q}',(np.quantile(a,q)-offset)/scale)
        add(f'{name}_mean',(a.mean()-offset)/scale);add(f'{name}_std',a.std()/scale)
    for level in ABS_LEVELS:add(f't_area_gt_{level}',np.mean(t>level))
    for level in DELTA_LEVELS:add(f'delta_area_gt_{level}',np.mean(d>level))
    add('positive_heat',np.maximum(d,0).mean()/10)
    add('strong_to_warm_area',np.mean(d>6)/max(np.mean(d>2),.01))
    return f,names

def one_features(x):
    x=np.asarray(x,dtype=np.float32)
    if x.shape!=(2,48,64) or not np.isfinite(x).all():raise ValueError('Expected finite [2,48,64] thermal input')
    t=gaussian_filter(x[0],sigma=.6);d=gaussian_filter(x[1],sigma=.6)
    f,names=region_features(t,d)
    # Coarse quadrants retain broad context, not a detailed pose-specific 4x4 grid.
    for r in range(2):
        for c in range(2):
            a=d[r*24:(r+1)*24,c*32:(c+1)*32]
            for level in [2.,4.,6.]:
                f.append(float((a>level).mean()));names.append(f'quadrant_{r}_{c}_delta_area_gt_{level}')
            f.append(float(np.maximum(a,0).mean()/10));names.append(f'quadrant_{r}_{c}_positive_heat')
    # Shift-invariant connected-region size/extent, never use the centroid as a class feature.
    for level in [2.,4.,6.]:
        mask=d>level;components,n=label(mask);sizes=np.bincount(components.ravel());sizes[0]=0
        largest=components==int(sizes.argmax()) if n else np.zeros_like(mask)
        area=int(largest.sum());ys,xs=np.where(largest)
        bw=(xs.max()-xs.min()+1)/64 if area else 0.;bh=(ys.max()-ys.min()+1)/48 if area else 0.
        compact=area/(max(bw*64*bh*48,1))
        for suffix,v in [('largest_area',area/3072),('width',bw),('height',bh),('bbox_fill',compact),('other_area',float(mask.mean())-area/3072)]:
            f.append(float(v));names.append(f'delta_{level}_{suffix}')
    # Pool temperature inside the broad warm region; absolute position removed.
    mask=d>2
    if mask.sum()>=8:
        vals=t[mask];diff=d[mask]
        extra=[(np.quantile(vals,q)-20)/15 for q in [.1,.5,.9]]+[np.quantile(diff,q)/10 for q in [.1,.5,.9]]
    else:extra=[0.]*6
    for i,v in enumerate(extra):f.append(float(v));names.append(f'warm_region_stat_{i}')
    out=np.array(f,np.float32)
    if not np.isfinite(out).all():raise ValueError('Nonfinite engineered features')
    return out,names

def thermal_features(x):
    x=np.asarray(x,dtype=np.float32)
    if x.ndim!=4 or x.shape[1:]!=(2,48,64):raise ValueError('Expected [N,2,48,64]')
    return np.stack([one_features(a)[0] for a in x])
