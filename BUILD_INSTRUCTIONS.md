# Инструкция по сборке APK локально

## Требования

Перед началом установите:

1. **Node.js** (версия 16+)
   - Скачайте с https://nodejs.org/
   - Проверьте: `node --version`

2. **Java Development Kit (JDK 11)**
   - Скачайте с https://www.oracle.com/java/technologies/downloads/
   - Проверьте: `java -version`

3. **Android SDK**
   - Скачайте Android Studio с https://developer.android.com/studio
   - Установите API Level 33 и Build Tools 33.0.0
   - Установите переменную окружения `ANDROID_HOME`

## Пошаговая инструкция

### Шаг 1: Клонируйте репозиторий
```bash
git clone https://github.com/olegrubvv-pixel/number-games
cd number-games
```

### Шаг 2: Установите Capacitor CLI
```bash
npm install -g @capacitor/cli @capacitor/android
npm install
```

### Шаг 3: Инициализируйте Capacitor
```bash
npx cap init "Number Games" "com.numbergames.app" --web-dir .
```

### Шаг 4: Добавьте Android платформу
```bash
npx cap add android
```

Это создаст папку `android/` с полным проектом для Android.

### Шаг 5: Соберите APK
```bash
cd android
./gradlew assembleDebug
```

Сборка займёт 2-5 минут (первая сборка может быть дольше).

### Шаг 6: Найдите готовый APK

После успешной сборки APK будет по пути:
```
android/app/build/outputs/apk/debug/app-debug.apk
```

## Установка на телефон

### Способ 1: Через USB кабель
```bash
# Подключите Android-телефон с включенной отладкой
./gradlew installDebug
```

### Способ 2: Скопировать APK вручную
1. Найдите файл `app-debug.apk`
2. Скопируйте его на телефон
3. Откройте файл в файловом менеджере и установите

## Решение проблем

### Ошибка: "ANDROID_HOME не установлена"
```bash
# На Windows:
set ANDROID_HOME=C:\Users\YourName\AppData\Local\Android\Sdk

# На macOS/Linux:
export ANDROID_HOME=$HOME/Android/Sdk
```

### Ошибка: "Java not found"
Убедитесь, что JDK установлена и добавлена в PATH:
```bash
java -version
javac -version
```

### Сборка медленная
- Это нормально для первой сборки
- Последующие сборки будут быстрее
- Убедитесь, что достаточно места на диске (минимум 5GB)

## Использование готового APK

После сборки можете:
1. **Установить на свой телефон** и тестировать
2. **Поделиться APK** с другими (app-debug.apk)
3. **Публиковать в Google Play Store** (после подписания релиз-версии)

---

**Успехов! 🚀**
