# AORUS LCD Panel Tersine Mühendislik Günlüğü

Bu günlük, GIGABYTE AORUS RTX 5080 ICE LCD panelde görülen statik özel görsel bozulmasıyla ilgili araştırma sürecini özetler.

## Problem

Özel bir görsel yüklenebiliyordu, ancak panelin yalnızca üst bölgesi değişiyordu; alt bölüm zamanla siyah oluyor veya eski içerik olarak kalıyordu. İlk aşamada küçük siyah noktalar görünürken, sonraki yüklemelerde siyah alan büyüdü ve son durumda görüntünün yaklaşık alt yüzde 40'ı tamamen siyah kaldı.

Default GIF, Chibi ve firmware içi modlar tam ekran çalışmaya devam ettiği için LCD panelin fiziksel olarak tüm alanı çizebildiği anlaşıldı.

## İlk Gözlemler

- Özel görsel yükleme akışı tamamen başarısız olmuyordu; üst bölüm güncelleniyordu.
- Alt bölüm siyah kalıyordu.
- Default GIF ve Chibi tam ekran çalışıyordu.
- Panel fiziksel olarak görüntü gösterebiliyordu.

Bu nedenle ilk önemli sonuç şuydu:

> LCD panel donanımı büyük olasılıkla fiziksel olarak arızalı değildi.

## Elenen İlk Hipotezler

Erken aşamada şu olasılıklar değerlendirildi:

- Alt LCD satırlarının arızalanması
- LCD ribbon kablosu veya kontrolcü arızası
- Windows veya GCC tarafında eski cache kalması
- Panel görüntü durum dosyalarının bozulması
- Firmware yeniden kurulumu ile temizlenebilecek özel görsel depolama kalıntısı

Bu hipotezlerin önemli bir kısmı daha sonra zayıfladı veya elendi.

## Windows Cache ve GCC State İncelemesi

`GigabyteDownloadAssistant`, `GigabyteUpdateService`, System32 altındaki GIGABYTE bileşenleri ve GCC state dosyaları incelendi.

Öne çıkan dosya şuydu:

```text
418C_0_LcdSetting.dat
```

Bu dosyanın panel state bilgileri içerdiği görüldü: mode, display, image config ve GIF config gibi alanlar bulunuyordu. Bir süre problemin bozuk panelden ziyade Windows tarafındaki state veya cache bozulması olabileceği düşünüldü. Ancak daha sonra statik upload veri yolu ve panel flash davranışı bu açıklamanın tek başına yeterli olmadığını gösterdi.

## ExApi ve Reset Hattı

Firmware ve DLL analizi sırasında şu fonksiyon aileleri bulundu:

- `GvLcdExApi`
- `LcdControlEx`
- `GetFWVersionEx`
- `SetReset`

Bu aşamada gerçek panel reset komutunun ExApi hattında olabileceği düşünüldü. Ancak ExApi yolu, `0x76` slave adresinin shifted karşılığı olan `0xEC` üzerinden çalışıyordu ve cihaz bu hatta yanıt vermiyordu. Bu yüzden ExApi teorik olarak mevcut olsa da pratik bir çözüm yolu olmaktan çıktı.

## Firmware Yeniden Kurulum Teorisi

Benzer problem yaşayan bazı kullanıcıların paneli firmware yeniden kurulumu ile düzelttiği raporlandı. Bu nedenle şu LCD firmware sürümleri test edildi:

- F1.2
- F1.3
- F1.4

Sonuçta firmware yeniden kurulumu özel görsel alanını güvenilir biçimde silemedi. Bozuk özel görsel kalmaya devam etti.

Önemli sonuç:

> Firmware yeniden kurulumu, özel görsel depolaması için gerçek bir fabrika ayarı sıfırlaması gibi davranmıyor.

## Display Reader ve State Analizi

Firmware tersine mühendisliği sürecinde özel statik görsel frame'inin başlangıcı olarak şu adres belirlendi:

```text
0x0130000C
```

Bu bölge 320x170 RGB565 frame verisine karşılık geliyordu. Reader tarafında okumaların yaklaşık `0x500` baytlık bloklar halinde yapıldığı gözlemlendi.

Bu aşamada şu firmware state alanları incelendi:

- `state+0x43`
- `state+0x48`
- `state+0x4C`
- `state+0x78`

Bir süre `state+0x43` alanının statik görsel enable veya validity gate gibi davrandığı düşünüldü. Upload sırasında kapanıp finalize sonrasında tekrar açılmıyor olabileceği hipotezi test edildi. Ancak görüntünün üst kısmının hâlâ değişiyor olması, bunun ana kök neden olmasını zayıflattı.

## Erase Timing Hipotezi

AP firmware içinde birçok erase işlemi bulundu. GCC tarafında upload akışında yaklaşık 5 saniyelik bir bekleme vardı. Bu nedenle payload yazımının erase tamamlanmadan başlamış olabileceği hipotezi kuruldu.

Test edilen varyantlar:

- Upload akışına ek 30 saniyelik bekleme eklemek
- Mevcut 5 saniyelik beklemeyi 10 saniyeye çıkarmak
- Daha doğal retry ve pacing denemeleri

Sonuç:

- Panel bozulması devam etti.
- Bekleme süresi kök neden olarak doğrulanmadı.
- Bazı varyantlarda crash ve tutarsız state gözlemlendi.

İlk somut write hatası şu bölgede görüldü:

```text
idx=312/426 GvWriteI2C fail
```

Bu, panelde siyah kalan bölgeyle kabaca eşleşiyordu.

## 0x01310000 Sınırı

Araştırma ilerledikçe şu adres öne çıktı:

```text
0x01310000
```

Bu sınır, görüntünün üst yüzde 60'ı ile alt yüzde 40'ı arasındaki bölünmeye yakın bir noktaya karşılık geliyordu. İlk yorum, firmware içinde özel bir boundary veya bank transition olabileceğiydi. Daha sonraki analizler bunun bir semptom sınırı olduğunu ve gerçek nedenin upload header içindeki erase-mode seçimiyle ilişkili olduğunu gösterdi.

## SPI Flash Bulguları

Firmware içinde şu SPI flash komutları gözlemlendi:

```text
0x06 Write Enable
0x05 Read Status
0x02 Page Program
0x20 Sector Erase
0xD8 Block Erase
0x03 Read
```

Buna karşılık status register write/protection bakım komutları net biçimde görünmüyordu. Bu durum, flash status/protection yönetiminin eksik veya zayıf olabileceği hipotezini doğurdu.

Kritik firmware bulgularından biri page-program helper fonksiyonunda görüldü:

- SPI status poll sonucu bazı yollarda ignore edilebiliyordu.
- Flash write gerçekte başarısız olsa bile üst seviye fonksiyon başarı döndürebiliyordu.

Bu bulgu şu gözlemi açıklıyordu:

```text
426/426 chunk success
F2 finalize success
SendImage true
```

Buna rağmen paneldeki görüntü hâlâ bozuk kalabiliyordu.

## 4-Byte Address Mode

Firmware içinde `0xB7` komutu bulundu ve bu SPI flash için 4-byte address mode olarak yorumlandı. `0x01300000` gibi özel slot adresleri 24-bit sınırın üzerinde olduğu için address mode, bank transition ve flash write path davranışı özellikle yakından incelendi.

## Nihai Keşif

Uzun analiz sürecinden sonra statik görsel upload sırasında üretilen F1 header içindeki kritik alan bulundu:

```text
F1[0x11]
```

Orijinal GCC/ucVga davranışı:

```text
F1[0x11] = 0x02
```

Deneysel patch:

```text
F1[0x11] = 0x01
```

Sonuç:

- Panel tam ekran düzeldi.
- Üst bölüm doğru güncellendi.
- Alt bölüm doğru güncellendi.
- Statik özel görsel yeniden tam frame olarak yazıldı.

## Teknik Sonuç

Orijinal statik upload paketi:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
```

Çalışan paket:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 01 00
```

Bu tek baytlık değişiklik, AP firmware tarafında statik özel görsel upload için farklı bir erase/write path seçilmesini sağlıyor.

Yorum:

- `0x02` değeri büyük olasılıkla problemli 64 KB block erase path'ini seçiyor.
- `0x01` değeri 4 KB sector erase path'ini seçiyor.
- Statik görüntüdeki alt yüzde 40 bozulması, ikinci erase/write bölgesinin eski veri olarak kalmasıyla uyumlu.

## Nihai Değerlendirme

Birincil bug:

```text
GCC / ucVga static image upload header generation
```

İkincil firmware zayıflığı:

```text
AP firmware can accept write path failures as success without sufficiently verifying them.
```

Pratik çözüm:

```text
During static custom image upload, the F1[0x11] value should be 0x01 instead of 0x02.
```

Bu çalışma sonucunda problem, "özel görsel yüklendiğinde alt yüzde 40 siyah kalıyor" seviyesinden "F1 upload header byte 0x11 içindeki değer yanlış erase/write path seçiyor" seviyesine indirildi ve lokal testlerle doğrulandı.

## Kalan Çalışma

Statik özel görsel problemi çözüldü. Özel GIF tarafındaki bozulma ayrı bir araştırma konusu olarak kalıyor; bu patch ile GIF path'i bilerek değiştirilmedi.

## Devam Araştırması: Özel GIF ve Firmware Updater Bulguları

Statik özel görsel problemi `F1[0x11]` erase-path selector alanına indirgendikten sonra araştırma, çözülmemiş özel GIF path'i ve native firmware updater path'i üzerinde devam etti.

Statik özel görsel bozulması için dar kapsamlı, çalışan bir host-side fix mevcut. Özel GIF problemi ise hâlâ çözülmemiş durumda; fakat mevcut kanıtlar bunun aynı daha geniş panel-side flash erase/write alt sistemiyle ilişkili olduğunu gösteriyor. Buna rağmen GIF path'i, statik görsellerde kullanılan aynı bayt değişikliğini basitçe uygulayarak güvenli biçimde düzeltilemiyor.

### GIF Path Neden Farklı

Aynı `0x02 -> 0x01` değişikliğini GIF upload'larına uygulama yönündeki ilk denemeler başarılı olmadı.

Temel neden, `F1[0x11]` alanının yalnızca erase size veya erase helper seçmediğinin görülmesidir. AP firmware analizi, bu alanın GIF'e özgü timing, playback veya finalization state ile de ilişkili olduğunu düşündürüyor.

Başka bir ifadeyle:

```text
0x01 works for static image erase behavior.
0x02 appears to be expected by the GIF state machine.
```

Bu yüzden GIF upload'larını zorla statik tarzı `0x01` path'ine almak, panel firmware'in beklediği GIF'e özgü semantik davranışı kaldırabilir veya bozabilir.

Doğru bir GIF onarımı, GIF upload'larını host tarafında statik 4 KB erase path'ine zorlamak yerine panel firmware içindeki 64 KB erase helper'ın onarılmasını gerektirebilir. Böyle bir yaklaşım GIF upload semantiğini korurken güvenilir olmayan erase implementasyonunu devre dışı bırakabilir.

### AP Firmware 64 KB Erase Helper Adayı

Offline AP firmware analizi, muhtemel bir 64 KB erase helper fonksiyonu belirledi:

```text
AP function address: 0x0000B4D0
AP file offset:     0xA4D0
```

Olası bir onarım yönü, bu helper'ı içeride on altı güvenilir 4 KB sector erase yapan bir döngüyle değiştirmektir:

```text
64 KB erase(address)
    -> erase_4KB(address + 0x0000)
    -> erase_4KB(address + 0x1000)
    -> erase_4KB(address + 0x2000)
    ...
    -> erase_4KB(address + 0xF000)
```

Bu hâlâ offline araştırma adayıdır. Kullanıcıya dönük bir fix olarak yayımlanmamıştır.

### Firmware Updater Bulguları

#### 1. Native updater erase aralığı F1.4 AP image'ın tamamını kapsamıyor olabilir

Offline analiz sırasında ayrı bir firmware updater sorunu da bulundu.

Native LCD firmware updater şu sabit AP aralığını siliyor gibi görünüyor:

```text
0x1000..0xEFFF
```

Ancak F1.4 AP image şu aralığa kadar programlanıyor gibi görünüyor:

```text
0x1000..0xF3D7
```

Bu, F1.4 update path'inin sabit erase window dışındaki baytları programlıyor olabileceği anlamına gelir.

Bu uyumsuzluk, firmware reinstall veya firmware update davranışının neden tutarsız olabildiğini açıklamak için güçlü bir adaydır. Ayrıca firmware işlemi birkaç kez çalıştırıldığında panel state'inin bazen toparlanıp ilk çalıştırmada başarısız olmasını da açıklayabilir.

Bu bulgu özel GIF fix'inden ayrıdır; ancak firmware-level bir onarımın bu updater path'i üzerinden güvenli biçimde teslim edilmesi gerekeceği için önemlidir.

#### 2. AP custom GIF playback frame 0'dan değil, frame 1'den başlıyor ve frame 1'e sarıyor

AP custom GIF playback path'inde ikinci bir firmware-level davranış gözlemlendi: custom GIF playback frame 0'dan başlıyor gibi görünmüyor.

Bunun yerine playback frame 1'den başlıyor ve animasyon sona ulaşıp başa sardığında frame 0 yerine yine frame 1'e dönüyor.

Bu durum, frame 0'ın AP GIF state machine içinde özel bir role sahip olabileceğini düşündürüyor. Örneğin frame 0; initialization, staging, metadata, first-frame priming veya normal playback dışı başka bir görev için kullanılıyor olabilir. En azından frame indexing şu aşamada basit bir host-side `0..N-1` playback sırası olarak ele alınamaz.

Bu önemlidir; çünkü host-side GIF conversion, frame ordering, reset deneyleri ve cleanup deneyleri, panel firmware'in runtime playback sırasında frame 0'ı kasıtlı olarak atlıyor veya rezerve ediyor olabileceği ihtimalini hesaba katmalıdır.

#### 3. `F1[0x11]=0x02`, güvenilir olmayan erase/program path için şu anki en güçlü model olmaya devam ediyor

Şu anki en güçlü model, `F1[0x11]=0x02` değerinin AP firmware'e 64 KB block erase path'ini seçtirmesidir.

Kritik sorun yalnızca bu path'in statik görseldeki 4 KB sector erase path'inden farklı olması değildir. İlgili helper rutinler failure status bilgisini de ignore ediyor gibi görünmektedir:

```text
64 KB erase helper:
    status poll result is ignored

page program helper:
    status poll result is ignored
```

Bunun sonucunda AP/GCC, flash bölgesinin bir kısmı gerçekte silinmemiş veya başarıyla programlanmamış olsa bile operasyon başarılı olmuş gibi raporlayabilir veya akışa devam edebilir.

Bu model gözlemlenen failure pattern ile eşleşir: yaklaşık `0x01310000` civarı ve sonrasındaki veri eski kalabilir veya kısmen yazılmamış olabilir; buna rağmen upload akışı host tarafından başarılı görünür.

Statik görsel fix'i bu modeli güçlü biçimde destekler. İlgili statik path `0x02` değerinden `0x01` değerine değiştirildiğinde operasyon 64 KB block erase path'inden 4 KB sector erase path'ine geçti ve tam görsel doğru şekilde yazıldı.

Bu nedenle en olası kök neden yalnızca GIF dosya boyutu, GIF encoding veya GCC upload UI davranışı değildir. Daha güçlü açıklama, `0x02` path'i üzerinde AP-side flash erase/program güvenilirlik problemidir: status polling failure durumları enforce edilmemekte ve panel flash içindeki eski içerik nominal olarak başarılı görünen bir upload sonrasında bile kalabilmektedir.
