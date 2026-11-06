"""Create an embedded blue Deleter by AI icon for the Windows EXE."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

size = 512
im = Image.new("RGBA", (size,size), (6,13,28,255))
draw = ImageDraw.Draw(im)
draw.rounded_rectangle((14,14,498,498), radius=115, fill=(10,23,46), outline=(38,123,225), width=7)
draw.ellipse((105,105,407,407), outline=(36,163,255), width=15)
draw.arc((105,105,407,407), 15, 230, fill=(101,230,255), width=20)
draw.rounded_rectangle((183,170,218,342), radius=12, fill=(222,245,255))
draw.arc((171,169,352,340), -90, 90, fill=(219,246,255), width=29)
draw.ellipse((357,111,388,142), fill=(101,230,255))
path=Path("assets/deleter_icon.ico")
path.parent.mkdir(parents=True,exist_ok=True)
im.save(path, format="ICO", sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
print("Icon ready:",path)
