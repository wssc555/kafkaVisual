import {beforeEach, describe, expect, it, vi} from 'vitest'

// ---- Types for mocks ----
type MockFn = ReturnType<typeof vi.fn>

/** 通用 mock axios 工厂：Part 2/3 各 describe 复用，减少样板 */
const makeAxiosMock = (fns: Record<string, MockFn>) => ({
  default: {
    create: () => ({
      ...fns,
      interceptors: {
        response: { use: () => {} },
      },
    }),
  },
})

/** 新增 API 的标准 mock 集（含 put / patch，覆盖全部动词） */
const fullMock = () => {
  const fns = {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  }
  return { fns, axiosMock: makeAxiosMock(fns) }
}

// ============================================================
// Part 1: Interceptor tests (TC-FE-INT-*)
// ============================================================
// These tests exercise the actual interceptors.response.use callbacks
// registered by api/index.ts, by importing the real axios and capturing
// the callbacks via a spy on interceptors.response.use.
// ============================================================

describe('Response interceptors (TC-FE-INT-*)', () => {
  let onFulfilled: ((res: any) => any) | undefined
  let onRejected: ((err: any) => Promise<never>) | undefined
  let createSpy: MockFn

  beforeEach(async () => {
    vi.resetModules()
    onFulfilled = undefined
    onRejected = undefined
    createSpy = vi.fn().mockImplementation((config: any) => ({
      get: vi.fn(),
      post: vi.fn(),
      delete: vi.fn(),
      interceptors: {
        response: {
          use: (fulfilled: any, rejected: any) => {
            onFulfilled = fulfilled
            onRejected = rejected
          },
        },
      },
    }))

    vi.doMock('axios', () => ({
      default: { create: createSpy },
    }))

    await import('../index')
  })

  // TC-FE-INT-001
  it('TC-FE-INT-001 onFulfilled returns res.data (strips axios wrapper)', () => {
    const res = {
      data: { code: 0, msg: 'ok', data: { clusterId: 'abc' } },
      status: 200,
      statusText: 'OK',
      headers: {},
      config: {},
    }
    expect(onFulfilled).toBeDefined()
    expect(onFulfilled!(res)).toEqual({
      code: 0,
      msg: 'ok',
      data: { clusterId: 'abc' },
    })
    expect(onFulfilled!(res)).toBe(res.data)
  })

  // TC-FE-INT-002
  it('TC-FE-INT-002 onRejected prefers err.response.data.msg', async () => {
    const err = {
      response: {
        data: { code: 40401, msg: 'Topic not found', data: null },
      },
    }
    expect(onRejected).toBeDefined()
    await expect(onRejected!(err)).rejects.toThrow('Topic not found')
  })

  // TC-FE-INT-007
  it('TC-FE-INT-007 onRejected preserves business code and HTTP status on ApiError', async () => {
    const err = {
      response: {
        status: 503,
        data: { code: 50301, msg: 'Consumer pool exhausted' },
      },
    }

    await expect(onRejected!(err)).rejects.toMatchObject({
      name: 'ApiError',
      message: 'Consumer pool exhausted',
      code: 50301,
      status: 503,
    })
  })

  // TC-FE-INT-003
  it('TC-FE-INT-003 onRejected falls back to err.message when no response', async () => {
    const err = { message: 'Network Error' }
    await expect(onRejected!(err)).rejects.toThrow('Network Error')
  })

  // TC-FE-INT-004
  it('TC-FE-INT-004 onRejected falls back to "Request failed" when everything missing', async () => {
    const err = {}
    await expect(onRejected!(err)).rejects.toThrow('Request failed')
  })

  // TC-FE-INT-005
  it('TC-FE-INT-005 onRejected falls back to err.message when response.data.msg missing', async () => {
    const err = {
      response: { data: { code: 50000 } },
      message: 'Internal Server Error',
    }
    await expect(onRejected!(err)).rejects.toThrow('Internal Server Error')
  })

  // TC-FE-INT-006
  it('TC-FE-INT-006 axios.create is called with baseURL and timeout', () => {
    expect(createSpy).toHaveBeenCalledTimes(1)
    expect(createSpy).toHaveBeenCalledWith({
      baseURL: '/api',
      timeout: 15000,
    })
  })
})

// ============================================================
// Part 2: API function tests (TC-FE-CLS/TOP/MSG/CG/ZK)
// ============================================================
// These tests verify request construction (URL, method, params, body).
// Mock resolves directly with the "post-interceptor" value (i.e. the
// backend body), since interceptor logic is already covered by INT-*.
//
// 功能端点全部挂集群段 `/c/{clusterId}`，
// 断言统一用 clusterId=1；集群段自身用模板字符串拼装、无用户可控分段，
// topic/group 等路径分段仍必须 encodeURIComponent。
// ============================================================

describe('Cluster API (TC-FE-CLS-*)', () => {
  let mockGet: MockFn
  let api: typeof import('../index')

  beforeEach(async () => {
    vi.resetModules()
    const {fns, axiosMock} = fullMock()
    mockGet = fns.get
    vi.doMock('axios', () => axiosMock)
    api = await import('../index')
  })

  // TC-FE-CLS-001
  it('TC-FE-CLS-001 getClusterInfo calls GET /c/1/cluster/info', async () => {
    const body = {
      code: 0,
      msg: 'ok',
      data: { clusterId: 'abc', controllerId: 1, brokers: [] },
    }
    mockGet.mockResolvedValue(body)

    const result = await api.getClusterInfo(1)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/cluster/info')
    expect(result).toEqual(body)
  })

  // TC-FE-CLS-002
  it('TC-FE-CLS-002 getClusterMode calls GET /c/1/cluster/mode', async () => {
    const body = { code: 0, msg: 'ok', data: { mode: 'KRAFT', zkAvailable: false } }
    mockGet.mockResolvedValue(body)

    const result = await api.getClusterMode(1)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/cluster/mode')
    expect(result).toEqual(body)
  })
})

describe('Cluster registry API (TC-FE-REG-*)', () => {
  let mockGet: MockFn
  let mockPost: MockFn
  let mockPut: MockFn
  let mockDelete: MockFn
  let api: typeof import('../index')

  beforeEach(async () => {
    vi.resetModules()
    const {fns, axiosMock} = fullMock()
    mockGet = fns.get
    mockPost = fns.post
    mockPut = fns.put
    mockDelete = fns.delete
    vi.doMock('axios', () => axiosMock)
    api = await import('../index')
  })

  // TC-FE-REG-001
  it('TC-FE-REG-001 getClusters calls GET /clusters (no cluster segment)', async () => {
    const body = { code: 0, msg: 'ok', data: [{ id: 1, name: 'dev', displayState: 'ONLINE' }] }
    mockGet.mockResolvedValue(body)

    const result = await api.getClusters()

    expect(mockGet).toHaveBeenCalledWith('/clusters')
    expect(result).toEqual(body)
  })

  // TC-FE-REG-002
  it('TC-FE-REG-002 getClusterStatuses calls GET /clusters/status', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: [] })

    await api.getClusterStatuses()

    expect(mockGet).toHaveBeenCalledWith('/clusters/status')
  })

  // TC-FE-REG-003
  it('TC-FE-REG-003 createCluster posts payload as-is to /clusters', async () => {
    const req = {
      name: 'dev',
      bootstrapServers: 'localhost:9092',
      securityProtocol: 'SASL_PLAINTEXT',
      saslMechanism: 'PLAIN',
      username: 'admin',
      password: 'secret',
      zkConnectString: '',
      archiveEnabled: true,
      archiveRetentionDays: 30,
    }
    mockPost.mockResolvedValue({ code: 0, msg: 'ok', data: { id: 1 } })

    await api.createCluster(req)

    expect(mockPost).toHaveBeenCalledWith('/clusters', req)
  })

  // TC-FE-REG-004
  it('TC-FE-REG-004 updateCluster PUTs to /clusters/{id}', async () => {
    const req = { name: 'dev', bootstrapServers: 'localhost:9093' }
    mockPut.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.updateCluster(7, req)

    expect(mockPut).toHaveBeenCalledWith('/clusters/7', req)
  })

  // TC-FE-REG-005
  it('TC-FE-REG-005 deleteCluster / connect / disconnect hit /clusters/{id} variants', async () => {
    mockDelete.mockResolvedValue({ code: 0, msg: 'ok', data: {} })
    mockPost.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.deleteCluster(3)
    await api.connectCluster(3)
    await api.disconnectCluster(3)

    expect(mockDelete).toHaveBeenCalledWith('/clusters/3')
    expect(mockPost).toHaveBeenCalledWith('/clusters/3/connect')
    expect(mockPost).toHaveBeenCalledWith('/clusters/3/disconnect')
  })
})

describe('Topic API (TC-FE-TOP-*)', () => {
  let mockGet: MockFn
  let mockPost: MockFn
  let mockDelete: MockFn
  let api: typeof import('../index')

  beforeEach(async () => {
    vi.resetModules()
    const {fns, axiosMock} = fullMock()
    mockGet = fns.get
    mockPost = fns.post
    mockDelete = fns.delete
    vi.doMock('axios', () => axiosMock)
    api = await import('../index')
  })

  // TC-FE-TOP-001
  it('TC-FE-TOP-001 getTopics default includeInternal=false', async () => {
    const body = { code: 0, msg: 'ok', data: ['topic-a', 'topic-b'] }
    mockGet.mockResolvedValue(body)

    const result = await api.getTopics(1)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/topics', {
      params: { includeInternal: false },
    })
    expect(result).toEqual(body)
  })

  // TC-FE-TOP-002
  it('TC-FE-TOP-002 getTopics includeInternal=true', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: [] })

    await api.getTopics(1, true)

    expect(mockGet).toHaveBeenCalledWith('/c/1/topics', {
      params: { includeInternal: true },
    })
  })

  // TC-FE-TOP-003
  it('TC-FE-TOP-003 getTopicDetail with normal name', async () => {
    const body = {
      code: 0,
      msg: 'ok',
      data: { name: 'topic-a', partitions: [] },
    }
    mockGet.mockResolvedValue(body)

    const result = await api.getTopicDetail(1, 'topic-a')

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/topics/topic-a')
    expect(result).toEqual(body)
  })

  // TC-FE-TOP-004（F10 修复后语义反转：路径参数统一 encodeURIComponent）
  it('TC-FE-TOP-004 getTopicDetail encodes special characters in path segment', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.getTopicDetail(1, 'a/b c')

    // `/` 与空格必须转义，否则 `a/b c` 会被路由切成两段
    expect(mockGet).toHaveBeenCalledWith('/c/1/topics/a%2Fb%20c')
  })

  // TC-FE-TOP-004b
  it('TC-FE-TOP-004b path encoding escapes % ? # as well', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.getTopicDetail(1, 'a%b?c#d')

    expect(mockGet).toHaveBeenCalledWith('/c/1/topics/a%25b%3Fc%23d')
  })

  // TC-FE-TOP-009
  it('TC-FE-TOP-009 deleteTopic encodes name in path', async () => {
    mockDelete.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.deleteTopic(1, 'a b')

    expect(mockDelete).toHaveBeenCalledWith('/c/1/topics/a%20b')
  })

  // TC-FE-TOP-010
  it('TC-FE-TOP-010 getTopicConfigs encodes topic in path', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.getTopicConfigs(1, 'a%b')

    expect(mockGet).toHaveBeenCalledWith('/c/1/cluster/metadata/topic-configs/a%25b')
  })

  // TC-FE-TOP-011
  it('TC-FE-TOP-011 expandPartitions encodes topic and keeps {partitions} body', async () => {
    mockPost.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.expandPartitions(1, 'a#b', 6)

    expect(mockPost).toHaveBeenCalledWith('/c/1/topics/a%23b/partitions', { partitions: 6 })
  })

  // TC-FE-TOP-005
  it('TC-FE-TOP-005 createTopic with configs passes body as-is', async () => {
    const req = {
      name: 't1',
      partitions: 3,
      replicationFactor: 2,
      configs: { 'retention.ms': '86400000' },
    }
    const body = { code: 0, msg: 'ok', data: { name: 't1', created: true } }
    mockPost.mockResolvedValue(body)

    const result = await api.createTopic(1, req)

    expect(mockPost).toHaveBeenCalledTimes(1)
    expect(mockPost).toHaveBeenCalledWith('/c/1/topics', req)
    expect(result).toEqual(body)
  })

  // TC-FE-TOP-006
  it('TC-FE-TOP-006 createTopic without configs omits configs from body', async () => {
    const req = { name: 't1', partitions: 1, replicationFactor: 1 }
    mockPost.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.createTopic(1, req)

    expect(mockPost).toHaveBeenCalledWith('/c/1/topics', req)
    // configs 字段不应出现在请求体中
    expect((mockPost.mock.calls[0][1] as any).configs).toBeUndefined()
  })

  // TC-FE-TOP-007
  it('TC-FE-TOP-007 deleteTopic returns data.deleted=true', async () => {
    const body = {
      code: 0,
      msg: 'ok',
      data: { name: 'topic-a', deleted: true },
    }
    mockDelete.mockResolvedValue(body)

    const result = await api.deleteTopic(1, 'topic-a')

    expect(mockDelete).toHaveBeenCalledTimes(1)
    expect(mockDelete).toHaveBeenCalledWith('/c/1/topics/topic-a')
    expect(result.data.deleted).toBe(true)
  })

  // TC-FE-TOP-008
  it('TC-FE-TOP-008 expandPartitions posts partition count to topic partitions endpoint', async () => {
    const body = {
      code: 0,
      msg: 'ok',
      data: { topic: 'topic-a', oldPartitionCount: 3, newPartitionCount: 6 },
    }
    mockPost.mockResolvedValue(body)

    const result = await api.expandPartitions(1, 'topic-a', 6)

    expect(mockPost).toHaveBeenCalledTimes(1)
    expect(mockPost).toHaveBeenCalledWith('/c/1/topics/topic-a/partitions', { partitions: 6 })
    expect(result).toEqual(body)
  })
})

describe('Message API (TC-FE-MSG-*)', () => {
  let mockGet: MockFn
  let mockPost: MockFn
  let api: typeof import('../index')

  beforeEach(async () => {
    vi.resetModules()
    const {fns, axiosMock} = fullMock()
    mockGet = fns.get
    mockPost = fns.post
    vi.doMock('axios', () => axiosMock)
    api = await import('../index')
  })

  // TC-FE-MSG-001
  it('TC-FE-MSG-001 queryMessages passes all four params', async () => {
    const params = { topic: 'topic-a', partition: 0, offset: 1000, count: 100 }
    const body = {
      code: 0,
      msg: 'ok',
      data: {
        topic: 'topic-a',
        partition: 0,
        startOffset: 1000,
        records: [],
        totalReturned: 100,
        endOffset: 1100,
        hasMore: true,
      },
    }
    mockGet.mockResolvedValue(body)

    const result = await api.queryMessages(1, params)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/messages', { params })
    expect(result).toEqual(body)
    // 类型断言：确保是数字而非字符串
    const calledParams = (mockGet.mock.calls[0][1] as any).params
    expect(typeof calledParams.offset).toBe('number')
    expect(typeof calledParams.count).toBe('number')
  })

  // TC-FE-MSG-002
  it('TC-FE-MSG-002 queryMessages does not mutate input params', () => {
    const p = { topic: 't', partition: 0, offset: 0, count: 10 }
    const pSnapshot = JSON.parse(JSON.stringify(p))
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    api.queryMessages(1, p)

    expect(p).toEqual(pSnapshot)
    // 深相等检查
    expect(p.topic).toBe('t')
    expect(p.partition).toBe(0)
    expect(p.offset).toBe(0)
    expect(p.count).toBe(10)
  })

  // TC-FE-MSG-003
  it('TC-FE-MSG-003 produceMessage posts body as-is including optional partition and headers', async () => {
    const req = {
      topic: 'topic-a',
      partition: 1,
      key: 'order-1',
      value: '{"id":1}',
      timestamp: 1690000000000,
      headers: { traceId: 'abc', source: 'unit-test' },
    }
    const body = {
      code: 0,
      msg: 'ok',
      data: { topic: 'topic-a', partition: 1, offset: 42 },
    }
    mockPost.mockResolvedValue(body)

    const result = await api.produceMessage(1, req)

    expect(mockPost).toHaveBeenCalledTimes(1)
    expect(mockPost).toHaveBeenCalledWith('/c/1/messages', req)
    expect(result).toEqual(body)
  })

  // TC-FE-MSG-004
  it('TC-FE-MSG-004 produceMessage omits partition when caller leaves it undefined', async () => {
    const req = { topic: 'topic-a', key: '', value: 'hello' }
    mockPost.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.produceMessage(1, req)

    expect(mockPost).toHaveBeenCalledWith('/c/1/messages', req)
    expect('partition' in (mockPost.mock.calls[0][1] as any)).toBe(false)
  })
})

describe('ConsumerGroup API (TC-FE-CG-*)', () => {
  let mockGet: MockFn
  let mockPost: MockFn
  let mockDelete: MockFn
  let api: typeof import('../index')

  beforeEach(async () => {
    vi.resetModules()
    const {fns, axiosMock} = fullMock()
    mockGet = fns.get
    mockPost = fns.post
    mockDelete = fns.delete
    vi.doMock('axios', () => axiosMock)
    api = await import('../index')
  })

  // TC-FE-CG-001
  it('TC-FE-CG-001 listConsumerGroups calls GET /c/1/consumer-groups', async () => {
    const body = { code: 0, msg: 'ok', data: ['g1', 'g2'] }
    mockGet.mockResolvedValue(body)

    const result = await api.listConsumerGroups(1)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/consumer-groups')
    expect(result).toEqual(body)
  })

  // TC-FE-CG-002
  it('TC-FE-CG-002 describeConsumerGroup path order {group}/topics/{topic}', async () => {
    const body = {
      code: 0,
      msg: 'ok',
      data: {
        group: 'g1',
        topic: 'topic-a',
        state: 'Stable',
        partitions: [],
        totalLag: 2345,
      },
    }
    mockGet.mockResolvedValue(body)

    const result = await api.describeConsumerGroup(1, 'g1', 'topic-a')

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/consumer-groups/g1/topics/topic-a')
    expect(result).toEqual(body)
  })

  // TC-FE-CG-003
  it('TC-FE-CG-003 describeConsumerGroup with hyphenated names', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.describeConsumerGroup(1, 'my-group', 'my-topic')

    expect(mockGet).toHaveBeenCalledWith(
      '/c/1/consumer-groups/my-group/topics/my-topic',
    )
  })

  // TC-FE-CG-004
  it('TC-FE-CG-004 getConsumerGroupOverview calls GET /consumer-groups/{group}', async () => {
    const body = {
      code: 0,
      msg: 'ok',
      data: {
        group: 'g1',
        state: 'Empty',
        memberCount: 0,
        topics: [
          { topic: 'topic-a', partitions: [], totalLag: 0 },
        ],
        totalLag: 0,
      },
    }
    mockGet.mockResolvedValue(body)

    const result = await api.getConsumerGroupOverview(1, 'g1')

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/consumer-groups/g1')
    expect(result).toEqual(body)
  })

  // TC-FE-CG-005
  it('TC-FE-CG-005 resetOffsets posts strategy body to group reset endpoint', async () => {
    const req = {
      topic: 'topic-a',
      strategy: 'TO_OFFSET',
      offset: 123,
      partitions: [0, 2],
    }
    const body = {
      code: 0,
      msg: 'ok',
      data: { group: 'g1', topic: 'topic-a', reset: [], resetCount: 0 },
    }
    mockPost.mockResolvedValue(body)

    const result = await api.resetOffsets(1, 'g1', req)

    expect(mockPost).toHaveBeenCalledTimes(1)
    expect(mockPost).toHaveBeenCalledWith('/c/1/consumer-groups/g1/offsets/reset', req)
    expect(result).toEqual(body)
  })

  // TC-FE-CG-006
  it('TC-FE-CG-006 resetOffsets preserves timestamp strategy payload shape', async () => {
    const req = { topic: 'topic-a', strategy: 'TO_TIMESTAMP', timestamp: 1690000000000 }
    mockPost.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.resetOffsets(1, 'g1', req)

    expect(mockPost).toHaveBeenCalledWith('/c/1/consumer-groups/g1/offsets/reset', req)
    expect((mockPost.mock.calls[0][1] as any).timestamp).toBe(1690000000000)
    expect((mockPost.mock.calls[0][1] as any).offset).toBeUndefined()
  })

  // TC-FE-CG-007
  it('TC-FE-CG-007 deleteConsumerGroup calls DELETE /consumer-groups/{group}', async () => {
    const body = { code: 0, msg: 'ok', data: { group: 'g1', deleted: true } }
    mockDelete.mockResolvedValue(body)

    const result = await api.deleteConsumerGroup(1, 'g1')

    expect(mockDelete).toHaveBeenCalledTimes(1)
    expect(mockDelete).toHaveBeenCalledWith('/c/1/consumer-groups/g1')
    expect(result).toEqual(body)
  })

  // TC-FE-CG-008（F10：消费组 ID 是任意字符串，`/` 未编码会打错路由）
  it('TC-FE-CG-008 describeConsumerGroup encodes group and topic segments', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.describeConsumerGroup(1, 'g/1', 'topic?a')

    expect(mockGet).toHaveBeenCalledWith('/c/1/consumer-groups/g%2F1/topics/topic%3Fa')
  })

  // TC-FE-CG-009
  it('TC-FE-CG-009 resetOffsets and deleteConsumerGroup encode group segment', async () => {
    mockPost.mockResolvedValue({ code: 0, msg: 'ok', data: {} })
    mockDelete.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.resetOffsets(1, 'g/1', { topic: 't', strategy: 'TO_LATEST' })
    await api.deleteConsumerGroup(1, 'g?1')

    expect(mockPost).toHaveBeenCalledWith(
      '/c/1/consumer-groups/g%2F1/offsets/reset',
      { topic: 't', strategy: 'TO_LATEST' },
    )
    expect(mockDelete).toHaveBeenCalledWith('/c/1/consumer-groups/g%3F1')
  })
})

describe('Dashboard / offsets / configs API (TC-FE-NEW-*)', () => {
  let mockGet: MockFn
  let mockPost: MockFn
  let mockPatch: MockFn
  let mockDelete: MockFn
  let api: typeof import('../index')

  beforeEach(async () => {
    vi.resetModules()
    const {fns, axiosMock} = fullMock()
    mockGet = fns.get
    mockPost = fns.post
    mockPatch = fns.patch
    mockDelete = fns.delete
    vi.doMock('axios', () => axiosMock)
    api = await import('../index')
  })

  // TC-FE-NEW-001
  it('TC-FE-NEW-001 getDashboardOverview calls GET /c/1/dashboard/overview', async () => {
    const body = {
      code: 0,
      msg: 'ok',
      data: {
        clusterId: 'abc123',
        mode: 'ZOOKEEPER',
        controllerId: 0,
        brokerCount: 3,
        topicCount: 12,
        internalTopicCount: 2,
        partitionCount: 36,
        underReplicatedPartitions: 1,
        offlinePartitions: 0,
        totalLogSizeBytes: 123456789,
        consumerGroupCount: 5,
      },
    }
    mockGet.mockResolvedValue(body)

    const result = await api.getDashboardOverview(1)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/dashboard/overview')
    expect(result.data.brokerCount).toBe(3)
    expect(result.data.totalLogSizeBytes).toBe(123456789)
  })

  // TC-FE-NEW-001b
  it('TC-FE-NEW-001b getMultiDashboard calls GET /dashboard/multi-overview (no cluster segment)', async () => {
    const body = {
      code: 0,
      msg: 'ok',
      data: [
        {
          clusterId: 1,
          name: 'dev',
          displayState: 'ONLINE',
          overview: { brokerCount: 3 },
          archivedMessages: null,
        },
        {
          clusterId: 2,
          name: 'edge',
          displayState: 'OFFLINE',
          overview: null,
          archivedMessages: 42,
        },
      ],
    }
    mockGet.mockResolvedValue(body)

    const result = await api.getMultiDashboard()

    expect(mockGet).toHaveBeenCalledWith('/dashboard/multi-overview')
    expect(result.data[1].archivedMessages).toBe(42)
  })

  // TC-FE-NEW-002
  it('TC-FE-NEW-002 offsetsForTimes passes topic+timestamp, omits partition when undefined', async () => {
    const body = {
      code: 0,
      msg: 'ok',
      data: {
        topic: 'my-topic',
        timestamp: 1690000000000,
        partitions: [{ partition: 0, offset: 5, matchTimestamp: 1690000001234 }],
      },
    }
    mockGet.mockResolvedValue(body)

    const result = await api.offsetsForTimes(1, { topic: 'my-topic', timestamp: 1690000000000 })

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/messages/offsets-for-times', {
      params: { topic: 'my-topic', timestamp: 1690000000000 },
    })
    // 未指定分区时不应发送 partition 字段（后端缺省 = 全部分区）
    const sent = (mockGet.mock.calls[0][1] as any).params
    expect('partition' in sent).toBe(false)
    expect(result.data.partitions[0].offset).toBe(5)
  })

  // TC-FE-NEW-003
  it('TC-FE-NEW-003 offsetsForTimes includes partition when specified', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: { partitions: [] } })

    await api.offsetsForTimes(1, { topic: 't', timestamp: 1, partition: 0 })

    expect(mockGet).toHaveBeenCalledWith('/c/1/messages/offsets-for-times', {
      params: { topic: 't', timestamp: 1, partition: 0 },
    })
  })

  // TC-FE-NEW-004
  it('TC-FE-NEW-004 updateTopicConfigs uses PATCH and passes configs body', async () => {
    const configs = { 'retention.ms': '86400000', 'cleanup.policy': null }
    const body = { code: 0, msg: 'ok', data: { topic: 'my-topic', updated: true } }
    mockPatch.mockResolvedValue(body)

    const result = await api.updateTopicConfigs(1, 'my-topic', { configs })

    expect(mockPatch).toHaveBeenCalledTimes(1)
    expect(mockPatch).toHaveBeenCalledWith('/c/1/cluster/metadata/topic-configs/my-topic', {
      configs,
    })
    // value=null 必须保留（表示删除覆盖项恢复默认），不能被过滤掉
    const sent = (mockPatch.mock.calls[0][1] as any).configs
    expect(sent['cleanup.policy']).toBeNull()
    expect(result.data.updated).toBe(true)
  })

  // TC-FE-NEW-005
  it('TC-FE-NEW-005 listZkChildren passes path and recursive=false by default', async () => {
    const body = { code: 0, msg: 'ok', data: { path: '/brokers', children: [] } }
    mockGet.mockResolvedValue(body)

    const result = await api.listZkChildren(1, '/brokers')

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/zk/children', {
      params: { path: '/brokers', recursive: false },
    })
    expect(result).toEqual(body)
  })

  // TC-FE-NEW-006
  it('TC-FE-NEW-006 listZkChildren passes recursive=true when requested', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: { children: [] } })

    await api.listZkChildren(1, '/brokers/topics', true)

    expect(mockGet).toHaveBeenCalledWith('/c/1/zk/children', {
      params: { path: '/brokers/topics', recursive: true },
    })
  })

  // TC-FE-NEW-007
  it('TC-FE-NEW-007 deleteZkNode calls DELETE /zk/node with path query param', async () => {
    const body = { code: 0, msg: 'ok', data: { path: '/old-node', deleted: true } }
    mockDelete.mockResolvedValue(body)

    const result = await api.deleteZkNode(1, '/old-node')

    expect(mockDelete).toHaveBeenCalledTimes(1)
    expect(mockDelete).toHaveBeenCalledWith('/c/1/zk/node', { params: { path: '/old-node' } })
    expect(result).toEqual(body)
  })

  // TC-FE-NEW-008
  it('TC-FE-NEW-008 getTopicConfigs calls metadata topic-configs endpoint', async () => {
    const body = { code: 0, msg: 'ok', data: { topic: 'topic-a', configs: {} } }
    mockGet.mockResolvedValue(body)

    const result = await api.getTopicConfigs(1, 'topic-a')

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/cluster/metadata/topic-configs/topic-a')
    expect(result).toEqual(body)
  })

  // TC-FE-NEW-009
  it('TC-FE-NEW-009 getBrokerConfigs calls metadata broker-configs endpoint', async () => {
    const body = { code: 0, msg: 'ok', data: { brokerId: 1, configs: {} } }
    mockGet.mockResolvedValue(body)

    const result = await api.getBrokerConfigs(1, 1)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/cluster/metadata/broker-configs/1')
    expect(result).toEqual(body)
  })

  // TC-FE-NEW-010
  it('TC-FE-NEW-010 getAcls calls metadata ACL endpoint', async () => {
    const body = { code: 0, msg: 'ok', data: [] }
    mockGet.mockResolvedValue(body)

    const result = await api.getAcls(1)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/cluster/metadata/acls')
    expect(result).toEqual(body)
  })

  // TC-FE-NEW-011
  it('TC-FE-NEW-011 getLogDirs calls metadata log-dirs endpoint', async () => {
    const body = { code: 0, msg: 'ok', data: [] }
    mockGet.mockResolvedValue(body)

    const result = await api.getLogDirs(1)

    expect(mockGet).toHaveBeenCalledTimes(1)
    // 无参调用仍兼容:params 为 undefined(axios 会省略 query string)
    expect(mockGet).toHaveBeenCalledWith('/c/1/cluster/metadata/log-dirs', { params: undefined })
    expect(result).toEqual(body)
  })

  // TC-FE-NEW-011b
  it('TC-FE-NEW-011b getLogDirs forwards brokerId/topic filters', async () => {
    const body = { code: 0, msg: 'ok', data: [] }
    mockGet.mockResolvedValue(body)

    await api.getLogDirs(1, { brokerId: 3, topic: 'orders' })

    expect(mockGet).toHaveBeenCalledWith('/c/1/cluster/metadata/log-dirs', {
      params: { brokerId: 3, topic: 'orders' },
    })
  })

  // TC-FE-NEW-011c（F9 修复后带 {params: undefined} 调用，axios 会把 undefined params 省略成无 query）
  it('TC-FE-NEW-011c getLogDirsSummary defaults to no query params', async () => {
    const body = { code: 0, msg: 'ok', data: [] }
    mockGet.mockResolvedValue(body)

    const result = await api.getLogDirsSummary(1)

    expect(mockGet).toHaveBeenCalledTimes(1)
    expect(mockGet).toHaveBeenCalledWith('/c/1/cluster/metadata/log-dirs/summary', {
      params: undefined,
    })
    expect(result).toEqual(body)
  })

  // TC-FE-NEW-011d（F9：透传 brokerId / includeInternal，与规范 §3.6 对齐）
  it('TC-FE-NEW-011d getLogDirsSummary forwards brokerId/includeInternal filters', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: [] })

    await api.getLogDirsSummary(1, { brokerId: 3, includeInternal: true })

    expect(mockGet).toHaveBeenCalledWith('/c/1/cluster/metadata/log-dirs/summary', {
      params: { brokerId: 3, includeInternal: true },
    })
  })

  // TC-FE-NEW-011e
  it('TC-FE-NEW-011e getLogDirsSummary omits unset optional params', async () => {
    mockGet.mockResolvedValue({ code: 0, msg: 'ok', data: [] })

    await api.getLogDirsSummary(1, { brokerId: 1 })

    const sent = (mockGet.mock.calls[0][1] as any).params
    expect(sent).toEqual({ brokerId: 1 })
    expect('includeInternal' in sent).toBe(false)
  })
})

describe('Storage / preference / archive / favorite API (TC-FE-STG-*)', () => {
  let fns: Record<string, MockFn>
  let api: typeof import('../index')

  beforeEach(async () => {
    vi.resetModules()
    const m = fullMock()
    fns = m.fns
    vi.doMock('axios', () => m.axiosMock)
    api = await import('../index')
  })

  // TC-FE-STG-001
  it('TC-FE-STG-001 getStorageConfig calls GET /storage', async () => {
    const body = {
      code: 0,
      msg: 'ok',
      data: { type: 'sqlite', activeType: 'sqlite', restartRequired: false, password: '' },
    }
    fns.get.mockResolvedValue(body)

    const result = await api.getStorageConfig()

    expect(fns.get).toHaveBeenCalledWith('/storage')
    expect(result).toEqual(body)
  })

  // TC-FE-STG-002
  it('TC-FE-STG-002 saveStorageConfig PUTs payload to /storage', async () => {
    const req = { type: 'postgresql', host: 'db.local', port: 5432, database: 'kv', username: 'u', password: 'p' }
    fns.put.mockResolvedValue({ code: 0, msg: 'saved', data: {} })

    await api.saveStorageConfig(req)

    expect(fns.put).toHaveBeenCalledWith('/storage', req)
  })

  // TC-FE-STG-003
  it('TC-FE-STG-003 testStorageConfig POSTs to /storage/test', async () => {
    const req = { type: 'mysql', host: 'db.local', port: 3306, database: 'kv', username: 'u', password: 'p' }
    fns.post.mockResolvedValue({ code: 0, msg: 'ok', data: { success: true, latencyMs: 12, version: '8.0.36' } })

    const result = await api.testStorageConfig(req)

    expect(fns.post).toHaveBeenCalledWith('/storage/test', req)
    expect(result.data.success).toBe(true)
  })

  // TC-FE-STG-004
  it('TC-FE-STG-004 getPreferences / putPreference hit /preferences', async () => {
    fns.get.mockResolvedValue({ code: 0, msg: 'ok', data: { asideCollapsed: 'true' } })
    fns.put.mockResolvedValue({ code: 0, msg: 'ok', data: {} })

    await api.getPreferences()
    await api.putPreference('asideCollapsed', 'false')

    expect(fns.get).toHaveBeenCalledWith('/preferences')
    expect(fns.put).toHaveBeenCalledWith('/preferences', { key: 'asideCollapsed', value: 'false' })
  })

  // TC-FE-STG-005
  it('TC-FE-STG-005 getArchiveTopics calls cluster-scoped archive topics endpoint', async () => {
    fns.get.mockResolvedValue({ code: 0, msg: 'ok', data: [] })

    await api.getArchiveTopics(2)

    expect(fns.get).toHaveBeenCalledWith('/c/2/archive/topics')
  })

  // TC-FE-STG-006
  it('TC-FE-STG-006 queryArchiveMessages forwards params incl. compound cursor', async () => {
    const params = {
      topic: 'orders',
      fromTime: 1000,
      toTime: 2000,
      offsetFromPartition: 3,
      offsetFrom: 41,
      limit: 100,
    }
    fns.get.mockResolvedValue({ code: 0, msg: 'ok', data: { records: [], nextPartition: 3, nextOffset: 41 } })

    await api.queryArchiveMessages(2, params)

    expect(fns.get).toHaveBeenCalledWith('/c/2/archive/messages', { params })
  })

  // TC-FE-STG-007
  it('TC-FE-STG-007 deleteArchive omits topic param when clearing whole cluster', async () => {
    fns.delete.mockResolvedValue({ code: 0, msg: 'ok', data: 10 })

    await api.deleteArchive(2)
    await api.deleteArchive(2, 'orders')

    expect(fns.delete).toHaveBeenNthCalledWith(1, '/c/2/archive', { params: undefined })
    expect(fns.delete).toHaveBeenNthCalledWith(2, '/c/2/archive', { params: { topic: 'orders' } })
  })

  // TC-FE-STG-008
  it('TC-FE-STG-008 favorites CRUD hits cluster-scoped endpoint; remove uses query params', async () => {
    fns.get.mockResolvedValue({ code: 0, msg: 'ok', data: [] })
    fns.post.mockResolvedValue({ code: 0, msg: 'ok', data: [] })
    fns.delete.mockResolvedValue({ code: 0, msg: 'ok', data: [] })

    await api.getFavorites(5)
    await api.addFavorite(5, 'topic', 'orders')
    await api.removeFavorite(5, 'group', 'g/1')

    expect(fns.get).toHaveBeenCalledWith('/c/5/favorites')
    expect(fns.post).toHaveBeenCalledWith('/c/5/favorites', { type: 'topic', name: 'orders' })
    // 消费组名任意字符串：query 参数由 axios 序列化，无需手动编码
    expect(fns.delete).toHaveBeenCalledWith('/c/5/favorites', { params: { type: 'group', name: 'g/1' } })
  })
})

// ============================================================
// Part 3: Error propagation tests (TC-FE-ERR-*)
// ============================================================
// These verify that HTTP errors propagate correctly through the
// API functions. We use mockRejectedValue with Error(msg) since
// interceptor logic is already covered in TC-FE-INT-*.
// ============================================================

describe('Error propagation (TC-FE-ERR-*)', () => {
  let mockGet: MockFn
  let api: typeof import('../index')

  beforeEach(async () => {
    vi.resetModules()
    const {fns, axiosMock} = fullMock()
    mockGet = fns.get
    vi.doMock('axios', () => axiosMock)
    api = await import('../index')
  })

  // TC-FE-ERR-001
  it('TC-FE-ERR-001 business error code rejects with Error(msg)', async () => {
    mockGet.mockRejectedValue(new Error('Topic not found: no-such'))

    await expect(api.getTopicDetail(1, 'no-such')).rejects.toThrow(
      'Topic not found: no-such',
    )
  })

  // TC-FE-ERR-002
  it('TC-FE-ERR-002 503 pool-full error message passes through', async () => {
    mockGet.mockRejectedValue(new Error('查询并发已满，请稍后重试'))

    await expect(
      api.queryMessages(1, { topic: 't', partition: 0, offset: 0, count: 100 }),
    ).rejects.toThrow('查询并发已满，请稍后重试')
  })

  // TC-FE-ERR-003
  it('TC-FE-ERR-003 network error falls back to err.message', async () => {
    mockGet.mockRejectedValue(new Error('Network Error'))

    await expect(api.getClusterInfo(1)).rejects.toThrow('Network Error')
  })

  // TC-FE-ERR-004
  it('TC-FE-ERR-004 cluster-not-found (40404) propagates as ApiError', async () => {
    mockGet.mockRejectedValue(new Error('cluster 99 not found'))

    await expect(api.getTopics(99)).rejects.toThrow('cluster 99 not found')
  })
})
