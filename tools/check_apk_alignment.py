import zipfile,struct,os
import sys
apk = sys.argv[1] if len(sys.argv) > 1 else 'app/build/outputs/apk/debug/app-debug.apk'
if not os.path.exists(apk):
    print('APK not found at',apk)
    print('\nUsage: python tools/check_apk_alignment.py [path/to/app.apk]')
    exit(2)

z=zipfile.ZipFile(apk)
libraries = ['lib/arm64-v8a/liboboe.so','lib/arm64-v8a/liboboe_synth.so']
print('Checking:', apk)
print()
for name in libraries:
    try:
        info=z.getinfo(name)
    except KeyError:
        print(name,'NOT FOUND')
        continue
    # compute file data offset: local header size = 30 + filename_len + extra_len
    with open(apk,'rb') as f:
        f.seek(info.header_offset)
        hdr = f.read(30)
        if len(hdr) < 30:
            print(name,'failed to read local header')
            continue
        filename_len = struct.unpack_from('<H',hdr,26)[0]
        extra_len = struct.unpack_from('<H',hdr,28)[0]
        data_offset = info.header_offset + 30 + filename_len + extra_len
    print(name)
    print('  compress_type:', info.compress_type, '(0 = stored/uncompressed)')
    print('  header_offset:', info.header_offset)
    print('  data_offset  :', data_offset)
    print('  data_offset % 16384 =', data_offset % 16384)
    print('  file_size    :', info.file_size)
    print('  compress_size:', info.compress_size)
    print()
