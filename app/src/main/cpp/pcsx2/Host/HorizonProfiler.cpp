// SPDX-FileCopyrightText: 2002-2026 PCSX2 Dev Team
// SPDX-License-Identifier: GPL-3.0+

// Sampling profiler for the Switch port.
//
// A small high-priority thread wakes up every few milliseconds, pauses each emulation thread
// (EE, GS, VU) for a few microseconds, reads its program counter and call chain, and lets it
// go again. Every few seconds it writes "[PROF]" lines to the log saying where each thread
// spent its time:
//   * which recompiler code cache it was executing (EE, IOP, VU0, VU1, VIF unpack...),
//   * which native code (offsets into the main module's text, in 64-byte buckets) and who
//     called it,
//   * which system call it was sitting in, and from where,
//   * for the EE, which guest blocks were hot, plus a hex dump of the hottest ones.
//
// The NRO carries no symbols, so native code is reported as offsets. They are resolved offline
// against the ELF of the same build (the build workflow publishes it next to the NRO).
//
// Rule for everything between pausing a thread and resuming it: system calls and plain memory
// reads only. No locks, no allocation, no logging - the paused thread may hold any of those.

#include "Host/HorizonProfiler.h"

#include "Common.h"
#include "VMManager.h"

#include "common/Console.h"
#include "common/Horizon/HorizonTuning.h"

#include "fmt/format.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "common/Horizon/Horizon.h"

// A known address inside the main module's text. Used to find the module at runtime and to
// cross-check logged offsets against the symbol table of the build.
extern "C" __attribute__((noinline, used)) void HorizonProfilerAnchor()
{
	__asm__ volatile("");
}

namespace
{
	// Sampling period: uniform in [3.5, 6.5] ms, so it cannot fall in step with the frame.
	constexpr u64 SAMPLE_PERIOD_MIN_NS = 3'500'000;
	constexpr u64 SAMPLE_PERIOD_JITTER_NS = 3'000'000;
	constexpr u64 REPORT_INTERVAL_NS = 10'000'000'000ULL;
	constexpr u64 MIN_PARTIAL_REPORT_NS = 3'000'000'000ULL;
	constexpr u64 TARGET_REFRESH_NS = 1'000'000'000ULL;
	constexpr s64 IDLE_POLL_NS = 100'000'000;

	constexpr size_t SAMPLER_STACK_SIZE = 0x20000;
	constexpr s32 MOST_URGENT_PRIORITY = 28; // lowest number hbloader's NPDM allows

	constexpr size_t MAX_CHAIN = 10; // callers kept for a thread sitting in a system call
	constexpr size_t RUNNING_CHAIN = 4; // callers kept for a running thread

	constexpr size_t FLAT_TOP = 48;
	constexpr size_t STACK_TOP = 12;
	constexpr size_t GUEST_TOP = 16;
	constexpr size_t GUEST_DUMP_BLOCKS = 4;
	constexpr u32 GUEST_DUMP_WORDS = 32;

	constexpr u32 FLAT_BUCKET_MASK = ~static_cast<u32>(63);

	// A "location" is a code address squeezed into 32 bits: an offset into the main module's
	// text, a marker for one of the recompiler code caches, or "somewhere else".
	constexpr u32 LOC_NONE = 0xFFFFFFFFu;
	constexpr u32 LOC_JIT = 0xF0000000u; // | ProfArea
	constexpr u32 HEAD_SVC = 0xE0000000u; // | system call number (stack heads only)
	constexpr u32 NOT_IN_SVC = 0xFFFFFFFFu;

	enum ProfArea : u32
	{
		AREA_EE,
		AREA_IOP,
		AREA_VIF0,
		AREA_VIF1,
		AREA_VU0,
		AREA_VU1,
		AREA_VIF_UNPACK,
		AREA_SW,
		AREA_NATIVE,
		AREA_KERNEL,
		AREA_OTHER,
		AREA_COUNT,
	};

	constexpr std::array<const char*, AREA_COUNT> AREA_NAMES = {
		"ee", "iop", "vif0", "vif1", "vu0", "vu1", "unpack", "sw", "native", "kernel", "other"};

	struct CodeMap
	{
		uptr text_begin = 0;
		uptr text_end = 0;
		uptr jit_begin = 0;
		uptr jit_end = 0;
	};

	u32 JitArea(size_t offset)
	{
		if (offset < HostMemoryMap::IOPrecOffset)
			return AREA_EE;
		if (offset < HostMemoryMap::VIF0recOffset)
			return AREA_IOP;
		if (offset < HostMemoryMap::VIF1recOffset)
			return AREA_VIF0;
		if (offset < HostMemoryMap::mVU0recOffset)
			return AREA_VIF1;
		if (offset < HostMemoryMap::mVU1recOffset)
			return AREA_VU0;
		if (offset < HostMemoryMap::VIFUnpackRecOffset)
			return AREA_VU1;
		if (offset < HostMemoryMap::SWrecOffset)
			return AREA_VIF_UNPACK;
		return AREA_SW;
	}

	u32 Locate(const CodeMap& map, uptr addr)
	{
		if (addr >= map.text_begin && addr < map.text_end)
			return static_cast<u32>(addr - map.text_begin);
		if (addr >= map.jit_begin && addr < map.jit_end)
			return LOC_JIT | JitArea(addr - map.jit_begin);
		return LOC_NONE;
	}

	bool IsNativeLoc(u32 loc)
	{
		return loc < HEAD_SVC;
	}

	bool IsJitLoc(u32 loc)
	{
		return loc != LOC_NONE && loc >= LOC_JIT;
	}

	constexpr bool IsSvcInstruction(u32 insn)
	{
		return (insn & 0xFFE0001Fu) == 0xD4000001u;
	}

	constexpr u32 SvcNumber(u32 insn)
	{
		return (insn >> 5) & 0xFFFFu;
	}

	// --------------------------------------------------------------------------------------
	//  Counting tables. Fixed size, no allocation; a slot is free while its count is zero.
	// --------------------------------------------------------------------------------------
	template <size_t SLOTS>
	struct CountTable
	{
		static_assert((SLOTS & (SLOTS - 1)) == 0, "SLOTS must be a power of two");

		struct Slot
		{
			u32 key;
			u32 count;
		};

		std::array<Slot, SLOTS> slots;
		u32 used;
		u32 dropped;

		void Clear()
		{
			std::memset(slots.data(), 0, sizeof(Slot) * SLOTS);
			used = 0;
			dropped = 0;
		}

		void Add(u32 key)
		{
			const size_t start = (key * 0x9E3779B1u) >> 12;
			for (size_t n = 0; n < SLOTS; n++)
			{
				Slot& slot = slots[(start + n) & (SLOTS - 1)];
				if (slot.count == 0)
				{
					if (used >= (SLOTS / 4) * 3)
						break;
					slot.key = key;
					slot.count = 1;
					used++;
					return;
				}
				if (slot.key == key)
				{
					slot.count++;
					return;
				}
			}
			dropped++;
		}
	};

	struct StackKey
	{
		u32 head; // HEAD_SVC | number, or the 64-byte bucket of the running code
		u32 depth;
		std::array<u32, MAX_CHAIN> chain; // callers, nearest first; unused entries are zero
	};

	template <size_t SLOTS>
	struct StackTable
	{
		static_assert((SLOTS & (SLOTS - 1)) == 0, "SLOTS must be a power of two");

		struct Slot
		{
			StackKey key;
			u32 count;
		};

		std::array<Slot, SLOTS> slots;
		u32 used;
		u32 dropped;

		void Clear()
		{
			std::memset(slots.data(), 0, sizeof(Slot) * SLOTS);
			used = 0;
			dropped = 0;
		}

		void Add(const StackKey& key)
		{
			u32 hash = 2166136261u;
			const auto mix = [&hash](u32 value) {
				hash = (hash ^ value) * 16777619u;
			};
			mix(key.head);
			mix(key.depth);
			for (const u32 value : key.chain)
				mix(value);

			const size_t start = hash ^ (hash >> 15);
			for (size_t n = 0; n < SLOTS; n++)
			{
				Slot& slot = slots[(start + n) & (SLOTS - 1)];
				if (slot.count == 0)
				{
					if (used >= (SLOTS / 4) * 3)
						break;
					slot.key = key;
					slot.count = 1;
					used++;
					return;
				}
				if (std::memcmp(&slot.key, &key, sizeof(StackKey)) == 0)
				{
					slot.count++;
					return;
				}
			}
			dropped++;
		}
	};

	struct ThreadProfile
	{
		u32 total;
		u32 missed;
		std::array<u32, AREA_COUNT> area;
		CountTable<4096> flat; // native code, by 64-byte bucket of the module text
		CountTable<4096> guest; // EE only: guest PC of the block being executed
		StackTable<1024> stacks;

		void Clear()
		{
			total = 0;
			missed = 0;
			area.fill(0);
			flat.Clear();
			guest.Clear();
			stacks.Clear();
		}
	};

	struct Target
	{
		const char* label = "";
		const char* registry_name = nullptr; // null: fixed handle (the EE/main thread)
		bool is_ee = false;
		bool announced = false;
		Handle handle = INVALID_HANDLE;
		u64 thread_id = 0;
		ThreadProfile profile;
	};

	// What is read from a paused thread.
	struct RawSample
	{
		uptr pc;
		uptr lr;
		uptr fp;
		uptr sp;
		u32 guest_pc;
		u32 svc;
		u32 depth;
		uptr chain[MAX_CHAIN];
	};

	struct State
	{
		::Thread thread = {};
		std::atomic_bool stop{false};
		CodeMap map;
		std::array<Target, 3> targets;
		std::array<u32, 128> dumped_blocks{};
		size_t dumped_count = 0;
		u64 pause_ticks = 0;
		u32 pause_count = 0;
	};

	std::unique_ptr<State> s_state;

	// --------------------------------------------------------------------------------------
	//  Sampling
	// --------------------------------------------------------------------------------------
	bool Capture(Handle handle, const CodeMap& map, bool is_ee, RawSample* out)
	{
		if (R_FAILED(svcSetThreadActivity(handle, ThreadActivity_Paused)))
			return false;

		// ---- The target is stopped from here on. System calls and plain reads only. ----
		ThreadContext ctx = {};
		const bool ok = R_SUCCEEDED(svcGetThreadContext3(&ctx, handle));
		if (ok)
		{
			out->pc = ctx.pc.x;
			out->lr = ctx.lr;
			out->fp = ctx.fp;
			out->sp = ctx.sp;
			out->guest_pc = is_ee ? cpuRegs.pc : 0;
			out->svc = NOT_IN_SVC;
			out->depth = 0;

			// Only native code follows the AArch64 frame-record convention and can contain
			// system calls; recompiled code is free to use x29/x30 as it likes.
			const uptr pc = out->pc;
			if ((pc & 3) == 0 && pc >= map.text_begin && (pc + 4) <= map.text_end)
			{
				// The kernel reports a thread that is inside a system call at the svc
				// instruction itself.
				const u32 at_pc = *reinterpret_cast<const u32*>(pc);
				if (IsSvcInstruction(at_pc))
				{
					out->svc = SvcNumber(at_pc);
				}
				else if (pc >= (map.text_begin + 4))
				{
					const u32 before_pc = *reinterpret_cast<const u32*>(pc - 4);
					if (IsSvcInstruction(before_pc))
						out->svc = SvcNumber(before_pc);
				}

				// Walk the frame records, never leaving the mapping the stack pointer is in.
				MemoryInfo info = {};
				u32 page_info = 0;
				if (R_SUCCEEDED(svcQueryMemory(&info, &page_info, out->sp)) && (info.perm & Perm_R) != 0)
				{
					const uptr low = out->sp;
					const uptr high = info.addr + info.size;
					uptr fp = out->fp;
					while (out->depth < MAX_CHAIN && (fp & 7) == 0 && fp >= low && fp < high && (high - fp) >= 16)
					{
						const uptr* const record = reinterpret_cast<const uptr*>(fp);
						const uptr next_fp = record[0];
						out->chain[out->depth++] = record[1];
						if (next_fp <= fp)
							break;
						fp = next_fp;
					}
				}
			}
		}

		svcSetThreadActivity(handle, ThreadActivity_Runnable);
		// ---- The target is running again. ----
		return ok;
	}

	void Accumulate(ThreadProfile& profile, const RawSample& sample, const CodeMap& map, bool is_ee)
	{
		profile.total++;

		const u32 loc = Locate(map, sample.pc);
		if (loc == LOC_NONE)
		{
			profile.area[AREA_OTHER]++;
			return;
		}

		if (IsJitLoc(loc))
		{
			const u32 area = std::min<u32>(loc & 0xFFu, AREA_SW);
			profile.area[area]++;
			if (is_ee && area == AREA_EE)
				profile.guest.Add(sample.guest_pc);
			return;
		}

		StackKey key = {};
		size_t limit;
		if (sample.svc != NOT_IN_SVC)
		{
			profile.area[AREA_KERNEL]++;
			key.head = HEAD_SVC | sample.svc;
			limit = MAX_CHAIN;
		}
		else
		{
			profile.area[AREA_NATIVE]++;
			profile.flat.Add(loc & FLAT_BUCKET_MASK);
			key.head = loc & FLAT_BUCKET_MASK;
			limit = RUNNING_CHAIN;
		}

		// Callers. The link register comes first: it is the caller of a leaf function (a system
		// call stub, memcpy, a small helper called from recompiled code). In a function that
		// has made calls of its own it points back into that function or holds a temporary, so
		// it is skipped when it is not a code address or repeats the first frame record.
		bool walking = true;
		const auto push = [&](uptr addr) {
			const u32 caller = Locate(map, addr);
			key.chain[key.depth++] = caller;
			walking = IsNativeLoc(caller);
		};

		const bool lr_repeats_frame = sample.depth > 0 && sample.chain[0] == sample.lr;
		if (!lr_repeats_frame && Locate(map, sample.lr) != LOC_NONE)
			push(sample.lr);
		for (u32 i = 0; i < sample.depth && walking && key.depth < limit; i++)
			push(sample.chain[i]);

		profile.stacks.Add(key);
	}

	void SampleTarget(State& state, Target& target)
	{
		if (target.handle == INVALID_HANDLE)
			return;

		// Make sure the handle still names the thread that registered it.
		u64 thread_id = 0;
		if (R_FAILED(svcGetThreadId(&thread_id, target.handle)) || thread_id != target.thread_id)
		{
			target.handle = INVALID_HANDLE;
			return;
		}

		RawSample sample;
		const u64 before = armGetSystemTick();
		const bool ok = Capture(target.handle, state.map, target.is_ee, &sample);
		state.pause_ticks += armGetSystemTick() - before;
		state.pause_count++;

		if (ok)
			Accumulate(target.profile, sample, state.map, target.is_ee);
		else
			target.profile.missed++;
	}

	// --------------------------------------------------------------------------------------
	//  Targets and code map
	// --------------------------------------------------------------------------------------
	void AnnounceTarget(Target& target)
	{
		target.announced = true;

		s32 priority = -1;
		s32 ideal_core = -1;
		u64 core_mask = 0;
		svcGetThreadPriority(&priority, target.handle);
		svcGetThreadCoreMask(&ideal_core, &core_mask, target.handle);
		INFO_LOG("[PROF] sampling thread {}: id {}, priority {}, ideal core {}, core mask {:#x}", target.label,
			target.thread_id, priority, ideal_core, core_mask);
	}

	void RefreshTargets(State& state)
	{
		Horizon::ThreadRecord records[48];
		const size_t count = Horizon::GetRegisteredThreads(records, std::size(records));

		for (Target& target : state.targets)
		{
			if (!target.registry_name)
			{
				if (!target.announced)
					AnnounceTarget(target);
				continue;
			}

			for (size_t i = 0; i < count; i++)
			{
				const Horizon::ThreadRecord& record = records[i];
				if (std::strcmp(record.name, target.registry_name) != 0)
					continue;

				if (target.handle != INVALID_HANDLE && target.thread_id == record.thread_id)
					break;

				// Only adopt the record if its handle still names that thread.
				u64 thread_id = 0;
				if (R_SUCCEEDED(svcGetThreadId(&thread_id, record.handle)) && thread_id == record.thread_id)
				{
					target.handle = record.handle;
					target.thread_id = record.thread_id;
					AnnounceTarget(target);
				}
				break;
			}
		}
	}

	void RefreshCodeMap(State& state)
	{
		const uptr jit_begin = reinterpret_cast<uptr>(SysMemory::GetCodePtr(0));
		if (jit_begin == state.map.jit_begin)
			return;

		state.map.jit_begin = jit_begin;
		state.map.jit_end = jit_begin ? (jit_begin + HostMemoryMap::CodeSize) : 0;
		INFO_LOG("[PROF] code map: module text {:#x}+{:#x} (anchor at +{:#x}), recompiler caches {:#x}+{:#x}",
			state.map.text_begin, state.map.text_end - state.map.text_begin,
			reinterpret_cast<uptr>(&HorizonProfilerAnchor) - state.map.text_begin, state.map.jit_begin,
			state.map.jit_end - state.map.jit_begin);
	}

	// --------------------------------------------------------------------------------------
	//  Reporting
	// --------------------------------------------------------------------------------------
	void AppendLoc(std::string& out, u32 loc)
	{
		if (loc == LOC_NONE)
			out += '?';
		else if (loc >= LOC_JIT)
			fmt::format_to(std::back_inserter(out), "J:{}", AREA_NAMES[std::min<u32>(loc & 0xFFu, AREA_SW)]);
		else if (loc >= HEAD_SVC)
			fmt::format_to(std::back_inserter(out), "svc:{:02x}", loc & 0xFFFFu);
		else
			fmt::format_to(std::back_inserter(out), "{:x}", loc);
	}

	template <size_t SLOTS>
	std::vector<std::pair<u32, u32>> TopCounts(const CountTable<SLOTS>& table, size_t limit)
	{
		std::vector<std::pair<u32, u32>> entries; // (count, key)
		entries.reserve(table.used);
		for (const auto& slot : table.slots)
		{
			if (slot.count != 0)
				entries.emplace_back(slot.count, slot.key);
		}
		std::sort(entries.begin(), entries.end(), [](const auto& a, const auto& b) {
			return (a.first != b.first) ? (a.first > b.first) : (a.second < b.second);
		});
		if (entries.size() > limit)
			entries.resize(limit);
		return entries;
	}

	bool ReadGuestCode(u32 pc, u32* out, u32 words)
	{
		if (!eeMem)
			return false;

		constexpr u32 ROM_BASE = 0x1FC00000u;
		const u32 phys = pc & 0x1FFFFFFFu;
		const u32 bytes = words * 4;
		const u8* source;
		if (phys < Ps2MemSize::MainRam && bytes <= (Ps2MemSize::MainRam - phys))
			source = eeMem->Main + phys;
		else if (phys >= ROM_BASE && (phys - ROM_BASE) < Ps2MemSize::Rom && bytes <= (Ps2MemSize::Rom - (phys - ROM_BASE)))
			source = eeMem->ROM + (phys - ROM_BASE);
		else
			return false;

		std::memcpy(out, source, bytes);
		return true;
	}

	void DumpGuestBlock(State& state, u32 pc)
	{
		const auto end = state.dumped_blocks.begin() + state.dumped_count;
		if (std::find(state.dumped_blocks.begin(), end, pc) != end || state.dumped_count >= state.dumped_blocks.size())
			return;
		state.dumped_blocks[state.dumped_count++] = pc;

		u32 words[GUEST_DUMP_WORDS];
		if (!ReadGuestCode(pc, words, GUEST_DUMP_WORDS))
			return;

		std::string line = fmt::format("[PROF] EE code {:08x}:", pc);
		for (const u32 word : words)
			fmt::format_to(std::back_inserter(line), " {:08x}", word);
		INFO_LOG("{}", line);
	}

	void ReportTarget(State& state, Target& target, double seconds)
	{
		const ThreadProfile& profile = target.profile;
		if (profile.total == 0)
		{
			if (target.handle == INVALID_HANDLE && target.registry_name)
				INFO_LOG("[PROF] {}: no thread to sample", target.label);
			return;
		}

		const double percent = 100.0 / static_cast<double>(profile.total);
		std::string line = fmt::format("[PROF] {}: {} samples in {:.1f}s (missed {}) |", target.label, profile.total,
			seconds, profile.missed);
		for (u32 area = 0; area < AREA_COUNT; area++)
		{
			if (profile.area[area] != 0)
				fmt::format_to(std::back_inserter(line), " {} {:.1f}%", AREA_NAMES[area], profile.area[area] * percent);
		}
		INFO_LOG("{}", line);

		// Native code, hottest 64-byte buckets first.
		const auto flat = TopCounts(profile.flat, FLAT_TOP);
		for (size_t i = 0; i < flat.size(); i += 16)
		{
			line = fmt::format("[PROF] {} native:", target.label);
			for (size_t j = i; j < flat.size() && j < (i + 16); j++)
				fmt::format_to(std::back_inserter(line), " {:x}={}", flat[j].second, flat[j].first);
			INFO_LOG("{}", line);
		}

		// Call chains: where the thread waits, and who calls the hot native code.
		std::vector<const StackTable<1024>::Slot*> stacks;
		stacks.reserve(profile.stacks.used);
		for (const auto& slot : profile.stacks.slots)
		{
			if (slot.count != 0)
				stacks.push_back(&slot);
		}
		std::sort(stacks.begin(), stacks.end(), [](const auto* a, const auto* b) { return a->count > b->count; });
		if (stacks.size() > STACK_TOP)
			stacks.resize(STACK_TOP);
		for (const auto* slot : stacks)
		{
			line = fmt::format("[PROF] {} stack {}: ", target.label, slot->count);
			AppendLoc(line, slot->key.head);
			for (u32 i = 0; i < slot->key.depth; i++)
			{
				line += " < ";
				AppendLoc(line, slot->key.chain[i]);
			}
			INFO_LOG("{}", line);
		}

		if (target.is_ee)
		{
			const auto guest = TopCounts(profile.guest, GUEST_TOP);
			if (!guest.empty())
			{
				line = "[PROF] EE guest:";
				for (const auto& [count, pc] : guest)
					fmt::format_to(std::back_inserter(line), " {:08x}={}", pc, count);
				INFO_LOG("{}", line);
			}

			// Show the code of the blocks that matter, once each.
			for (size_t i = 0; i < guest.size() && i < GUEST_DUMP_BLOCKS; i++)
			{
				if (guest[i].first * 33 >= profile.total)
					DumpGuestBlock(state, guest[i].second);
			}
		}

		if (profile.flat.dropped != 0 || profile.guest.dropped != 0 || profile.stacks.dropped != 0)
		{
			INFO_LOG("[PROF] {}: tables full, samples left out of the lists: native {} guest {} stack {}", target.label,
				profile.flat.dropped, profile.guest.dropped, profile.stacks.dropped);
		}
	}

	void ReportAll(State& state, u64 elapsed_ns)
	{
		const double seconds = static_cast<double>(elapsed_ns) / 1e9;
		for (Target& target : state.targets)
			ReportTarget(state, target, seconds);

		if (state.pause_count != 0)
		{
			INFO_LOG("[PROF] sampler: {} pauses, {:.1f} us each on average", state.pause_count,
				static_cast<double>(armTicksToNs(state.pause_ticks)) / 1000.0 / static_cast<double>(state.pause_count));
		}
	}

	void ClearWindow(State& state)
	{
		for (Target& target : state.targets)
			target.profile.Clear();
		state.pause_ticks = 0;
		state.pause_count = 0;
	}

	u64 NextRandom(u64& value)
	{
		value ^= value << 13;
		value ^= value >> 7;
		value ^= value << 17;
		return value;
	}

	void SamplerThread(void*)
	{
		State& state = *s_state;
		u64 random = armGetSystemTick() | 1;
		bool active = false;
		u64 window_start = 0;
		u64 last_refresh = 0;

		while (!state.stop.load(std::memory_order_acquire))
		{
			if (VMManager::GetState() != VMState::Running)
			{
				if (active)
				{
					// Leaving a game (or pausing it): keep what was gathered if it is enough to mean something.
					const u64 elapsed = armTicksToNs(armGetSystemTick() - window_start);
					if (elapsed >= MIN_PARTIAL_REPORT_NS)
						ReportAll(state, elapsed);
					active = false;
				}
				svcSleepThread(IDLE_POLL_NS);
				continue;
			}

			if (!active)
			{
				RefreshCodeMap(state);
				RefreshTargets(state);
				ClearWindow(state);
				window_start = last_refresh = armGetSystemTick();
				active = true;
			}
			else if (armTicksToNs(armGetSystemTick() - last_refresh) >= TARGET_REFRESH_NS)
			{
				RefreshTargets(state);
				last_refresh = armGetSystemTick();
			}

			svcSleepThread(static_cast<s64>(SAMPLE_PERIOD_MIN_NS + (NextRandom(random) % SAMPLE_PERIOD_JITTER_NS)));
			if (state.stop.load(std::memory_order_acquire))
				break;
			if (VMManager::GetState() != VMState::Running)
				continue;

			for (Target& target : state.targets)
				SampleTarget(state, target);

			const u64 elapsed = armTicksToNs(armGetSystemTick() - window_start);
			if (elapsed >= REPORT_INTERVAL_NS)
			{
				ReportAll(state, elapsed);
				ClearWindow(state);
				window_start = armGetSystemTick();
			}
		}
	}
} // namespace

void HorizonProfiler::Start()
{
	if (s_state)
		return;

	// svcSetThreadActivity / svcGetThreadContext3
	if (!envIsSyscallHinted(0x32) || !envIsSyscallHinted(0x33))
	{
		WARNING_LOG("[PROF] the loader does not allow pausing threads; profiler disabled");
		return;
	}

	auto state = std::make_unique<State>();

	// The module text is one mapping; find it through an address known to be inside it.
	MemoryInfo info = {};
	u32 page_info = 0;
	const uptr anchor = reinterpret_cast<uptr>(&HorizonProfilerAnchor);
	if (R_FAILED(svcQueryMemory(&info, &page_info, anchor)) || (info.perm & Perm_X) == 0 || info.size == 0)
	{
		WARNING_LOG("[PROF] could not locate the module text; profiler disabled");
		return;
	}
	state->map.text_begin = info.addr;
	state->map.text_end = info.addr + info.size;

	Target& ee = state->targets[0];
	ee.label = "EE";
	ee.is_ee = true;
	ee.handle = threadGetCurHandle();
	if (R_FAILED(svcGetThreadId(&ee.thread_id, ee.handle)))
	{
		WARNING_LOG("[PROF] could not identify the main thread; profiler disabled");
		return;
	}

	state->targets[1].label = "GS";
	state->targets[1].registry_name = "GS";
	state->targets[2].label = "VU";
	state->targets[2].registry_name = "MTVU";
	for (Target& target : state->targets)
		target.profile.Clear();

	// Above the emulation threads, so a sample is taken when the timer fires rather than when
	// a core happens to be free.
	s32 priority = 0x2C;
	if (R_FAILED(svcGetThreadPriority(&priority, CUR_THREAD_HANDLE)))
		priority = 0x2C;
	priority = std::max<s32>(priority - 2, MOST_URGENT_PRIORITY);

	Result rc = threadCreate(&state->thread, &SamplerThread, nullptr, nullptr, SAMPLER_STACK_SIZE, priority, -2);
	if (R_FAILED(rc))
	{
		WARNING_LOG("[PROF] threadCreate failed: {:#010x}; profiler disabled", static_cast<unsigned>(rc));
		return;
	}

	u64 core_mask = 0;
	if (R_SUCCEEDED(svcGetInfo(&core_mask, InfoType_CoreMask, CUR_PROCESS_HANDLE, 0)) && core_mask != 0)
		svcSetThreadCoreMask(state->thread.handle, -1, static_cast<u32>(core_mask));

	const uptr text_begin = state->map.text_begin;
	const uptr text_size = state->map.text_end - state->map.text_begin;

	// The thread reads s_state, so publish it before starting.
	s_state = std::move(state);
	rc = threadStart(&s_state->thread);
	if (R_FAILED(rc))
	{
		WARNING_LOG("[PROF] threadStart failed: {:#010x}; profiler disabled", static_cast<unsigned>(rc));
		threadClose(&s_state->thread);
		s_state.reset();
		return;
	}

	// hbloader unloads the NRO when main() returns; no thread of ours may outlive that, whichever
	// way the application leaves.
	static bool s_exit_hook_registered = false;
	if (!s_exit_hook_registered)
	{
		std::atexit([]() { HorizonProfiler::Stop(); });
		s_exit_hook_registered = true;
	}

	INFO_LOG("[PROF] sampling profiler started: priority {}, period 3.5-6.5 ms, module text {:#x}+{:#x} (anchor at +{:#x})",
		priority, text_begin, text_size, anchor - text_begin);
}

void HorizonProfiler::Stop()
{
	if (!s_state)
		return;

	s_state->stop.store(true, std::memory_order_release);
	threadWaitForExit(&s_state->thread);
	threadClose(&s_state->thread);
	s_state.reset();
}
