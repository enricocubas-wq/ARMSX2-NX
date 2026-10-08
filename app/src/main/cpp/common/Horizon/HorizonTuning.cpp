// SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

#include "common/Horizon/HorizonTuning.h"

#include "common/Console.h"
#include "common/Horizon/Horizon.h"

#include <cstring>
#include <mutex>

namespace Horizon
{
	namespace
	{
		// Default priority of the main thread under hbloader.
		constexpr s32 DEFAULT_MAIN_PRIORITY = 0x2C;
		// Range hbloader's NPDM allows (numerically lower is more urgent).
		constexpr s32 MOST_URGENT_PRIORITY = 28;
		// Preferred core for helper threads. They may still run on any allowed core; this is only
		// the scheduler's first choice.
		constexpr s32 HELPER_IDEAL_CORE = 1;

		std::atomic_bool s_thread_tuning{false};
		std::atomic<s32> s_emu_priority{DEFAULT_MAIN_PRIORITY};
		std::atomic<Thread*> s_main_thread{nullptr};
		std::atomic_bool s_code_page_read_backpatch{false};

		FaultStats s_fault_stats;
		ReprotectStats s_reprotect_stats;

		constexpr size_t MAX_REGISTERED_THREADS = 48;
		std::mutex s_registry_mutex;
		ThreadRecord s_registry[MAX_REGISTERED_THREADS];
		size_t s_registry_count = 0;
		size_t s_registry_next_victim = 0;

		enum class ThreadPolicy
		{
			None, ///< leave untouched (the EE/main thread)
			Emulation, ///< GS / VU: same priority as the EE thread
			Audio, ///< tiny periodic thread that must not starve
			Helper, ///< everything else
		};

		ThreadPolicy PolicyForName(const char* name)
		{
			if (!name)
				return ThreadPolicy::Helper;
			if (std::strcmp(name, "CPU Thread") == 0)
				return ThreadPolicy::None;
			if (std::strcmp(name, "GS") == 0 || std::strcmp(name, "MTVU") == 0)
				return ThreadPolicy::Emulation;
			if (std::strcmp(name, "Audio") == 0)
				return ThreadPolicy::Audio;
			return ThreadPolicy::Helper;
		}

		void SetCurrentThreadPriority(const char* name, s32 priority)
		{
			const Result rc = svcSetThreadPriority(CUR_THREAD_HANDLE, static_cast<u32>(priority));
			if (R_FAILED(rc))
			{
				WARNING_LOG("Thread '{}': svcSetThreadPriority({}) failed: {:#010x}", name, priority,
					static_cast<unsigned>(rc));
			}
		}

		// Keeps the calling thread on every core the process may use (libnx's default) and gives
		// it a preferred core.
		void SpreadCurrentThread(const char* name)
		{
			u64 allowed = 0;
			if (R_FAILED(svcGetInfo(&allowed, InfoType_CoreMask, CUR_PROCESS_HANDLE, 0)) || allowed == 0)
			{
				WARNING_LOG("Thread '{}': InfoType_CoreMask unavailable, leaving it on the default core", name);
				return;
			}

			const s32 ideal = (allowed & (static_cast<u64>(1) << HELPER_IDEAL_CORE)) ?
								  HELPER_IDEAL_CORE :
								  static_cast<s32>(__builtin_ctzll(allowed));
			const Result rc = svcSetThreadCoreMask(CUR_THREAD_HANDLE, ideal, static_cast<u32>(allowed));
			if (R_FAILED(rc))
			{
				WARNING_LOG("Thread '{}': svcSetThreadCoreMask(core {}, mask {:#x}) failed: {:#010x}", name, ideal,
					allowed, static_cast<unsigned>(rc));
			}
		}
	} // namespace

	void InitThreadTuning(bool enabled)
	{
		s32 priority = DEFAULT_MAIN_PRIORITY;
		if (R_FAILED(svcGetThreadPriority(&priority, CUR_THREAD_HANDLE)))
			priority = DEFAULT_MAIN_PRIORITY;

		s_main_thread.store(threadGetSelf(), std::memory_order_release);
		s_emu_priority.store(priority, std::memory_order_release);
		s_thread_tuning.store(enabled, std::memory_order_release);

		u64 allowed = 0;
		svcGetInfo(&allowed, InfoType_CoreMask, CUR_PROCESS_HANDLE, 0);
		INFO_LOG("Horizon thread tuning {} (main thread priority {}, core {}, allowed core mask {:#x})",
			enabled ? "enabled" : "disabled", priority, svcGetCurrentProcessorNumber(), allowed);
	}

	bool IsThreadTuningEnabled()
	{
		return s_thread_tuning.load(std::memory_order_acquire);
	}

	void ApplyThreadPolicy(const char* name)
	{
		if (!IsThreadTuningEnabled())
			return;

		// The EE runs on the main thread; never move it, whatever name it is given.
		if (threadGetSelf() == s_main_thread.load(std::memory_order_acquire))
			return;

		const char* const label = name ? name : "(unnamed)";
		const s32 emu_priority = s_emu_priority.load(std::memory_order_acquire);

		switch (PolicyForName(name))
		{
			case ThreadPolicy::None:
				return;

			case ThreadPolicy::Emulation:
				// The core is assigned later by VMManager::SetEmuThreadAffinities().
				SetCurrentThreadPriority(label, emu_priority);
				break;

			case ThreadPolicy::Audio:
				SpreadCurrentThread(label);
				SetCurrentThreadPriority(label, (emu_priority > MOST_URGENT_PRIORITY) ? (emu_priority - 1) : emu_priority);
				break;

			case ThreadPolicy::Helper:
				SpreadCurrentThread(label);
				break;
		}

		s32 priority = -1;
		s32 ideal = -1;
		u64 mask = 0;
		svcGetThreadPriority(&priority, CUR_THREAD_HANDLE);
		svcGetThreadCoreMask(&ideal, &mask, CUR_THREAD_HANDLE);
		INFO_LOG("Thread '{}': priority {}, ideal core {}, core mask {:#x}", label, priority, ideal, mask);
	}

	void SetCodePageReadBackpatchEnabled(bool enabled)
	{
		s_code_page_read_backpatch.store(enabled, std::memory_order_release);
	}

	bool IsCodePageReadBackpatchEnabled()
	{
		return s_code_page_read_backpatch.load(std::memory_order_acquire);
	}

	FaultStats& GetFaultStats()
	{
		return s_fault_stats;
	}

	ReprotectStats& GetReprotectStats()
	{
		return s_reprotect_stats;
	}

	void RegisterCurrentThread(const char* name)
	{
		if (!name || !name[0])
			return;

		ThreadRecord record = {};
		std::strncpy(record.name, name, sizeof(record.name) - 1);
		record.handle = threadGetCurHandle();
		if (R_FAILED(svcGetThreadId(&record.thread_id, record.handle)))
			return;

		std::lock_guard lock(s_registry_mutex);
		for (size_t i = 0; i < s_registry_count; i++)
		{
			// A thread that was restarted under the same name replaces the old entry.
			if (std::strncmp(s_registry[i].name, record.name, sizeof(record.name)) == 0)
			{
				s_registry[i] = record;
				return;
			}
		}

		if (s_registry_count < MAX_REGISTERED_THREADS)
		{
			s_registry[s_registry_count++] = record;
			return;
		}

		// Full (many short-lived uniquely named threads): recycle entries round-robin.
		s_registry[s_registry_next_victim] = record;
		s_registry_next_victim = (s_registry_next_victim + 1) % MAX_REGISTERED_THREADS;
	}

	size_t GetRegisteredThreads(ThreadRecord* out, size_t max_count)
	{
		std::lock_guard lock(s_registry_mutex);
		const size_t count = (s_registry_count < max_count) ? s_registry_count : max_count;
		for (size_t i = 0; i < count; i++)
			out[i] = s_registry[i];
		return count;
	}
} // namespace Horizon
