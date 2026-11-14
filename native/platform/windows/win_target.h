/* Must be included before <windows.h>: the APIs we use (GetLogicalProcessorInformationEx,
 * CPU sets, proc-thread attributes) need a Windows 7+ target; Windows 10 is what we test on. */
#ifndef WIN_TARGET_H
#define WIN_TARGET_H
#if !defined(_WIN32_WINNT) || _WIN32_WINNT < 0x0601
#undef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#if !defined(WINVER) || WINVER < 0x0601
#undef WINVER
#define WINVER 0x0A00
#endif
#endif
