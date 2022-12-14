#include <string.h>
#include <jni.h>

#include "hunspell/hunspell.hxx"

#ifdef __cplusplus
extern "C" {
#endif

//TODO: there are quite a few memory leaks here so clean it up if you'd like to use it for real

Hunspell* hunspell;

void Java_com_iwobanas_hunspellchecker_Hunspell_create( JNIEnv* env,
                                                  jobject thiz, jstring jaff, jstring jdic, jobject mutex )
{
	jboolean isCopy;
	const char *aff = env->GetStringUTFChars(jaff, &isCopy);
	const char *dic = env->GetStringUTFChars(jdic, &isCopy);

	env->MonitorEnter(mutex);
	if(hunspell != NULL) delete hunspell;
	hunspell = new Hunspell(aff, dic);
	env->MonitorExit(mutex);
}

jobjectArray Java_com_iwobanas_hunspellchecker_Hunspell_getSuggestions( JNIEnv* env,
                                                  jobject thiz, jstring jword )
{
    jclass jcls = env->FindClass("java/lang/String");

    jboolean isCopy;
    const char *word = env->GetStringUTFChars(jword, &isCopy);
    char **suggestions;
    int len = hunspell->suggest(&suggestions, word);

    jobjectArray jsuggestions = env->NewObjectArray(len, jcls, 0);

    for (int i = 0; i < len; i++)
    {
        env->SetObjectArrayElement(jsuggestions, i, env->NewStringUTF(suggestions[i]));
    }
    hunspell->free_list(&suggestions, len);
    return jsuggestions;
}
char* iso8859_1_to_utf8(const char *in)
{
    char *utf8 = (char*)malloc(1 + (2 * strlen(in)));

    if (utf8) {
        unsigned char *out = (unsigned char *)utf8;
        unsigned char *str = (unsigned char *)in;
        while (*str)
            if (*str<128) *out++=*str++;
            else {
            *out++=0xc2+(*str>0xbf), *out++=(*str++ & 0x3f)+0x80;
            }
        *out++ = '\0';
    }
    return utf8;
}

jobjectArray Java_com_iwobanas_hunspellchecker_Hunspell_analyze( JNIEnv* env,
                                                  jobject thiz, jstring jword )
{
    jclass jcls = env->FindClass("java/lang/String");

    jboolean isCopy;
    const char *word = env->GetStringUTFChars(jword, &isCopy);
    char **suggestions;
    int len = hunspell->analyze(&suggestions, word);

    jobjectArray jsuggestions = env->NewObjectArray(len, jcls, 0);

    for (int i = 0; i < len; i++)
    {
        char * utf8 = iso8859_1_to_utf8(suggestions[i]);
        env->SetObjectArrayElement(jsuggestions, i, env->NewStringUTF(utf8));
        free(utf8);
    }
    hunspell->free_list(&suggestions, len);
    return jsuggestions;
}

jint Java_com_iwobanas_hunspellchecker_Hunspell_spell( JNIEnv* env,
                                                  jobject thiz, jstring jword )
{
	jboolean isCopy;
	const char *word = env->GetStringUTFChars(jword, &isCopy);
	if(hunspell != NULL && word != NULL) {
	int result = hunspell->spell(word);
	return result;
	}
	return 0;
}

#ifdef __cplusplus
}
#endif
