#!/usr/bin/env python3
"""Build immutable, deterministic vanilla-client alpha.3 release assets."""
import argparse,hashlib,json,os,shutil,tempfile,zipfile
from pathlib import Path
from bootstrap_server import VERSION,validate,sha256,MAX_DOWNLOAD
from package_resources import pack
BASE='https://github.com/ProkStudio/WarLand/releases/download/v'+VERSION
JAR=f'warland-{VERSION}.jar'
README='''WarLand alpha.3 — Minecraft Java 1.21.11

ИГРОКАМ
Обычный клиент Minecraft Java 1.21.11. Fabric, клиентский мод и особый лаунчер не нужны.
Подключитесь к серверу, зарегистрируйтесь через встроенное окно и согласитесь на серверный ресурс-пак.
Minecraft скачает и проверит ресурсы сам. При неизменном пакете используется кэш клиента.
Пароль виден при вводе: используйте отдельный пароль только для WarLand, не показывайте экран посторонним.
Владелец назначается только через приватное подтверждение оператора, никогда по одному нику.

НОВЫЙ СЕРВЕР
Linux x86-64, Python 3.12+, доступ к HTTPS и достаточно памяти (рекомендуется 4 ГБ).
Прочитайте https://aka.ms/MinecraftEULA. Если согласны:
python3 bootstrap_server.py --directory WarLand-server --accept-eula
./WarLand-server/start.sh

Установщик скачивает WarLand, Fabric, Fabric API, Polymer и ресурс-пак по закреплённым SHA-256.
Если Java 21 отсутствует, скачивает проверенный Temurin JRE в приватную папку сервера.
Fabric при первом запуске автоматически получает Minecraft и свои библиотеки.
Не запускайте от root. Для публичного доступа откройте только игровой порт TCP 25565.
При обрыве повторите ту же команду: проверенные файлы используются повторно.
Установщик НЕ обновляет существующий сервер и НЕ перезаписывает миры, аккаунты или настройки.
Обновление существующей установки — только по docs/VANILLA_DEPLOY.md с резервной копией и откатом.

ГРАНИЦЫ ALPHA
Это ограниченный предварительный релиз, не готовность полного ТЗ. Незавершённые рынок, покупка зданий и захват отключены.
Используется штатное шифрование Minecraft; это не TLS, не AEAD и не закрепление доверенного ключа сервера.
Нужны отдельные публичные/beta/нагрузочные испытания перед полноценным production-релизом.

ЗАВИСИМОСТИ
Polymer: Patbox и contributors, LGPL-3.0-only, https://github.com/Patbox/polymer
Fabric: https://fabricmc.net/ ; исходники и лицензии https://github.com/FabricMC
Temurin/OpenJDK: GPL-2.0 with Classpath Exception, https://adoptium.net/ ; ссылка на точный выпуск в server-lock.json.
Зависимости получаются неизменёнными из источников разработчиков, не включены в этот архив.
Minecraft не включён в архив; распространяется и лицензируется Mojang/Microsoft.
'''

def check_jar(jar):
    if jar.is_symlink() or not jar.is_file() or jar.stat().st_size>MAX_DOWNLOAD:raise ValueError('Invalid executable JAR')
    with zipfile.ZipFile(jar) as z:
        info=z.getinfo('fabric.mod.json')
        if info.file_size>65536:raise ValueError('Oversized mod metadata')
        meta=json.loads(z.read(info))
        if meta.get('id')!='warland' or meta.get('version')!=VERSION:raise ValueError('Wrong mod/version')
        if 'ru/warland/auth/NativeAuthDialog.class' not in z.namelist():raise ValueError('Expected executable native-auth classes, not a sources JAR')
        if 'polymer-core' not in meta.get('depends',{}):raise ValueError('Polymer dependency missing')
    return jar

def bundle(root,jar,out):
    root=Path(root);jar=check_jar(Path(jar));out=Path(out)
    if out.is_symlink() or out.exists():raise ValueError('Release output already exists; never replace assets')
    pins=json.loads((root/'release/server-dependencies.json').read_text())
    out.parent.mkdir(parents=True,exist_ok=True)
    stage=Path(tempfile.mkdtemp(prefix='.warland-release-',dir=out.parent))
    try:
        shutil.copyfile(jar,stage/JAR)
        resource=pack(root/'src/main/resources/assets',stage/'resource-pack.zip')
        manifest={'schema':1,'version':VERSION,'files':pins['files']+[
            {'path':'mods/'+JAR,'url':BASE+'/'+JAR,'sha256':sha256(stage/JAR)},
            {'path':'resource-pack.zip','url':BASE+'/resource-pack.zip','sha256':resource['sha256']}],
            'java':pins['java'],'resource_pack_url':BASE+'/resource-pack.zip','resource_pack_sha1':resource['sha1']}
        validate(manifest)
        lock=json.dumps(manifest,ensure_ascii=False,sort_keys=True,indent=2)+'\n'
        (stage/'server-lock.json').write_text(lock,encoding='utf-8')
        (stage/'START_HERE_RU.txt').write_text(README,encoding='utf-8')
        files={'bootstrap_server.py':(root/'tools/bootstrap_server.py').read_bytes(),'server-lock.json':lock.encode(),'START_HERE_RU.txt':README.encode()}
        with zipfile.ZipFile(stage/f'warland-server-{VERSION}.zip','x',compression=zipfile.ZIP_STORED) as z:
            for name,data in sorted(files.items()):
                info=zipfile.ZipInfo(name,date_time=(2026,1,1,0,0,0));info.create_system=3;info.external_attr=0o100644<<16;z.writestr(info,data)
        sums=''.join(sha256(p)+'  '+p.name+'\n' for p in sorted(stage.iterdir()))
        (stage/'SHA256SUMS').write_text(sums,encoding='ascii')
        # An existing destination is never intentionally removed, merged or overwritten.
        if out.exists() or out.is_symlink():raise ValueError('Concurrent output appeared')
        stage.rename(out)
        return manifest
    finally:
        if stage.exists():shutil.rmtree(stage)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);p.add_argument('--jar',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    result=bundle(a.root,a.jar,a.out);print(json.dumps({'version':result['version'],'pack_sha1':result['resource_pack_sha1'],'output':str(a.out)},indent=2))
