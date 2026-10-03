# MarkView ships without code shrinking (isMinifyEnabled = false), so this file is
# intentionally empty. It is kept so that enabling R8 later has an obvious place for
# keep rules (the JavaScript interface is the only reflective surface):
#
# -keepclassmembers class com.markview.viewer.MarkViewBridge {
#     public *;
# }
