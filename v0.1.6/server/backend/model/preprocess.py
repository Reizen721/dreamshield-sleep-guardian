"""Thermal frame preprocessing used by the trained models."""
import math
import numpy as np
from PIL import Image

LABELS = ["empty", "covered", "partially_uncovered", "largely_uncovered"]
CN = ["无人/离床余温", "正常覆盖", "部分未覆盖", "大面积未覆盖"]

def crop_letterbox(a,roi):
    a=np.asarray(a,dtype=np.float32)
    if a.shape!=(62,80) or not np.isfinite(a).all():raise ValueError('Expected finite 62x80 Celsius matrix')
    x0=max(0,math.floor(roi['x']*80));x1=min(80,math.ceil((roi['x']+roi['width'])*80))
    y0=max(0,math.floor(roi['y']*62));y1=min(62,math.ceil((roi['y']+roi['height'])*62))
    crop=a[y0:y1,x0:x1]
    if min(crop.shape)<2:raise ValueError('Invalid bed ROI')
    scale=min(64/crop.shape[1],48/crop.shape[0]);w=max(1,round(crop.shape[1]*scale));h=max(1,round(crop.shape[0]*scale))
    out=np.asarray(Image.fromarray(crop).resize((w,h),Image.Resampling.BILINEAR),dtype=np.float32)
    return np.pad(out,((int((48-h)//2),int(48-h-(48-h)//2)),(int((64-w)//2),int(64-w-(64-w)//2))),mode='edge')

def extract_features(x):
    # Fixed physical feature layout; all estimator standardization is fit within each training fold.
    fs=[]
    for a in x:
        f=[]
        for channel in a:
            f.extend(np.quantile(channel,[0.05,0.25,0.5,0.75,0.9,0.95,0.99]).tolist())
            f.extend([float(channel.mean()),float(channel.std()),float(channel.max())])
            for row in np.array_split(channel,4,axis=0):
                for block in np.array_split(row,4,axis=1):f.extend([float(block.mean()),float(np.quantile(block,0.9))])
        fs.append(f)
    return np.asarray(fs,dtype=np.float32)

