# AORUS LCD Panel Reverse Engineering Günlüğü

Bu günlük, GIGABYTE AORUS RTX 5080 ICE LCD panelinde görülen static custom image bozulmasının araştırma sürecini özetler.

## Problem

Custom image yüklenebiliyor, ancak panelde yalnızca üst bölge değişiyor; alt kısım zamanla siyah veya stale kalıyordu. İlk aşamada küçük siyah lekeler görülürken, sonraki yüklemelerde siyah alan büyüdü ve son durumda görüntünün yaklaşık alt yüzde 40'ı tamamen siyah kaldı.

Default GIF, Chibi ve firmware içi modlar tam ekran çalışmaya devam ettiği için LCD panelin fiziksel olarak tüm alanı çizebildiği anlaşıldı.

## Başlangıç Gözlemleri

- Custom image upload akışı tamamen başarısız değildi; üst kısım güncelleniyordu.
- Alt bölge siyah kalıyordu.
- Default GIF ve Chibi tam ekran çalışıyordu.
- Panel fiziksel olarak görüntü verebiliyordu.

Bu nedenle ilk önemli çıkarım şuydu:

> LCD panel donanımı büyük olasılıkla fiziksel olarak arızalı değildi.

## Elenen İlk Hipotezler

İlk dönemde şu ihtimaller değerlendirildi:

- LCD alt satırlarının ölmesi
- LCD ribbon veya controller arızası
- Windows veya GCC tarafında stale cache
- Panel display state dosyalarının bozulması
- Firmware reinstall ile temizlenebilecek custom image storage kalıntısı

Bu hipotezlerin önemli kısmı daha sonra zayıfladı veya elendi.

## Windows Cache ve GCC State Araştırması

`GigabyteDownloadAssistant`, `GigabyteUpdateService`, System32 altındaki GIGABYTE bileşenleri ve GCC state dosyaları incelendi.

Öne çıkan dosya:

```text
418C_0_LcdSetting.dat
```

Bu dosyanın mode, display, image config ve GIF config gibi panel state bilgilerini içerdiği görüldü. Bir süre problemin bozuk panel yerine bozuk Windows-side state veya cache olabileceği düşünüldü. Ancak daha sonra static upload data path'i ve panel flash davranışı bu açıklamayı tek başına yeterli kılmadı.

## ExApi ve Reset Hattı

Firmware ve DLL analizlerinde şu fonksiyon aileleri bulundu:

- `GvLcdExApi`
- `LcdControlEx`
- `GetFWVersionEx`
- `SetReset`

Bu aşamada gerçek panel reset komutunun ExApi hattında olabileceği düşünüldü. Ancak ExApi yolu `0x76` slave adresinin shifted karşılığı olan `0xEC` üzerinden çalışıyordu ve cihaz bu hatta cevap vermiyordu. Bu nedenle ExApi, teorik olarak var olsa da pratik çözüm yolu olmaktan çıktı.

## Firmware Reinstall Teorisi

Benzer problem yaşayan kullanıcıların firmware reinstall ile paneli düzelttiği raporlandı. Bu yüzden LCD firmware sürümleri test edildi:

- F1.2
- F1.3
- F1.4

Sonuçta firmware reinstall custom image alanını güvenilir biçimde silmedi. Bozuk custom image kalmaya devam etti.

Önemli sonuç:

> Firmware reinstall, custom image storage için gerçek bir factory reset gibi davranmıyor.

## Display Reader ve State Analizi

Firmware reverse engineering sürecinde custom static image frame başlangıcı olarak şu adres belirlendi:

```text
0x0130000C
```

Bu bölge 320x170 RGB565 frame verisine karşılık geliyordu. Reader tarafında yaklaşık `0x500` byte bloklarla okuma yapıldığı görüldü.

Bu aşamada firmware state alanları incelendi:

- `state+0x43`
- `state+0x48`
- `state+0x4C`
- `state+0x78`

Bir süre `state+0x43` alanının static image enable veya validity gate gibi davrandığı düşünüldü. Upload sırasında kapanıp finalize sonrası tekrar açılmıyor olabilir hipotezi test edildi. Ancak görüntünün üst kısmının yine de değişmesi, bunu ana kök neden olmaktan çıkardı.

## Erase Timing Hipotezi

AP firmware içinde çok sayıda erase operasyonu bulundu. GCC tarafında ise upload akışında yaklaşık 5 saniyelik bekleme vardı. Bu nedenle erase tamamlanmadan payload yazımına geçiliyor olabilir hipotezi oluşturuldu.

Test edilen varyantlar:

- Upload akışına ek 30 saniye bekleme koymak
- Mevcut 5 saniyelik beklemeyi 10 saniyeye çıkarmak
- Daha doğal retry ve pacing denemeleri

Sonuç:

- Panel bozulması devam etti.
- Bekleme süresi kök neden olarak doğrulanmadı.
- Bazı varyantlarda crash ve tutarsız state gözlendi.

İlk somut yazma hatası şu bölgede görüldü:

```text
idx=312/426 GvWriteI2C fail
```

Bu yaklaşık olarak panelde siyah kalan bölgeyle örtüşüyordu.

## 0x01310000 Sınırı

Araştırma ilerledikçe şu adres öne çıktı:

```text
0x01310000
```

Bu sınır, görüntünün üst yüzde 60 ve alt yüzde 40 ayrımına yakın bir noktaya denk geliyordu. İlk yorum, burada firmware içinde özel bir sınır veya bank geçişi olabileceğiydi. Daha sonra yapılan analizler bunun semptom sınırı olduğunu, asıl sebebin upload header içindeki erase-mode seçimiyle ilişkili olduğunu gösterdi.

## SPI Flash Bulguları

Firmware içinde şu SPI flash komutları görüldü:

```text
0x06 Write Enable
0x05 Read Status
0x02 Page Program
0x20 Sector Erase
0xD8 Block Erase
0x03 Read
```

Buna karşılık status register write/protection bakım komutları açık biçimde görünmedi. Bu durum flash status/protection yönetiminin eksik veya zayıf olabileceği hipotezini doğurdu.

Kritik firmware bulgularından biri page-program helper fonksiyonunda görüldü:

- SPI status poll sonucu bazı yollarda yok sayılabiliyordu.
- Flash yazımı gerçekten başarısız olsa bile üst seviye fonksiyon success dönebiliyordu.

Bu bulgu şu gözlemi açıkladı:

```text
426/426 chunk success
F2 finalize success
SendImage true
```

ama panelde görüntü yine de bozuk kalabiliyordu.

## 4-Byte Address Mode

Firmware içinde `0xB7` komutu bulundu ve bu, SPI flash için 4-byte address mode olarak yorumlandı. Custom slot adreslerinin `0x01300000` gibi 24-bit sınırının üzerinde olması nedeniyle address mode, bank geçişleri ve flash write path davranışı özellikle incelendi.

## Nihai Keşif

Uzun süren analizler sonunda static image upload sırasında oluşturulan F1 header içinde kritik alan bulundu:

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
- Üst kısım doğru güncellendi.
- Alt kısım doğru güncellendi.
- Static custom image tekrar tam frame olarak yazıldı.

## Teknik Sonuç

Orijinal static upload packet'i:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 02 00
```

Çalışan packet:

```text
F1 CB 55 AC 38 01 30 00 00 01 00 00 01 AA 00 00 00 01 00
```

Bu tek byte değişimi static custom image upload için AP firmware tarafında farklı erase/write path seçtiriyor.

Yorum:

- `0x02` değeri büyük olasılıkla sorunlu 64 KB block erase yolunu seçtiriyor.
- `0x01` değeri 4 KB sector erase yolunu seçtiriyor.
- Static image alt yüzde 40 bozulması, ikinci erase/write bölgesinin stale kalmasıyla uyumlu.

## Son Hüküm

Birincil bug:

```text
GCC / ucVga static image upload header üretimi
```

İkincil firmware zayıflığı:

```text
AP firmware write path başarısızlıklarını yeterince doğrulamadan success kabul edebiliyor.
```

Pratik çözüm:

```text
Static custom image upload sırasında F1[0x11] değeri 0x02 yerine 0x01 olmalıdır.
```

Bu çalışma sonucunda problem, "custom image yüklenince alt yüzde 40 siyah kalıyor" seviyesinden, "F1 upload header byte 0x11 içindeki değer yanlış erase/write path seçtiriyor" seviyesine indirgenmiş ve yerel testlerle doğrulanmıştır.

## Kalan İş

Static custom image problemi çözülmüş durumdadır. Custom GIF tarafındaki bozulma ayrı bir araştırma konusu olarak kalmaktadır; GIF path'i bu patch ile bilerek değiştirilmemiştir.
