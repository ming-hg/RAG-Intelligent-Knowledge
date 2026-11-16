const fs = require('fs');
const path = require('path');
const { v4: uuidv4 } = require('uuid');

// 会话数据存储路径（本地JSON文件，无需数据库）
const sessionDir = path.join(__dirname, '../data');
const sessionFile = path.join(sessionDir, 'sessions.json');

// 初始化会话存储目录和文件
if (!fs.existsSync(sessionDir)) {
  fs.mkdirSync(sessionDir, { recursive: true });
}
if (!fs.existsSync(sessionFile)) {
  fs.writeFileSync(sessionFile, JSON.stringify([], null, 2), 'utf-8');
}

/**
 * 读取所有会话列表（按创建时间倒序）
 */
function getSessionList() {
  const sessions = JSON.parse(fs.readFileSync(sessionFile, 'utf-8'));
  // 按创建时间倒序，最新会话在前
  return sessions.sort((a, b) => new Date(b.createTime) - new Date(a.createTime));
}

/**
 * 创建新会话
 * @param {String} sessionName - 会话名称（可选，默认"新会话+时间"）
 * @returns {Object} 新会话信息
 */
function createSession(sessionName = '') {
  const sessions = getSessionList();
  const sessionId = uuidv4();
  const newSession = {
    sessionId,
    sessionName: sessionName || `新会话_${new Date().toLocaleString()}`,
    createTime: new Date().toISOString(),
    updateTime: new Date().toISOString(),
    messages: [] // 存储会话历史消息（用户+AI）
  };
  sessions.push(newSession);
  // 写入文件
  fs.writeFileSync(sessionFile, JSON.stringify(sessions, null, 2), 'utf-8');
  return newSession;
}

/**
 * 查询单个会话的历史消息
 * @param {String} sessionId - 会话ID
 * @returns {Object} 会话详情（含历史消息）
 */
function getSessionDetail(sessionId) {
  const sessions = getSessionList();
  const session = sessions.find(item => item.sessionId === sessionId);
  if (!session) {
    throw new Error('会话不存在');
  }
  return session;
}

/**
 * 新增会话历史消息
 * @param {String} sessionId - 会话ID
 * @param {Object} message - 消息对象（{role: 'user/assistant', content: '消息内容', time: '时间'}）
 */
function addSessionMessage(sessionId, message) {
  const sessions = getSessionList();
  const sessionIndex = sessions.findIndex(item => item.sessionId === sessionId);
  if (sessionIndex === -1) {
    throw new Error('会话不存在');
  }
  // 补充消息时间
  const newMessage = {
    ...message,
    time: new Date().toLocaleString()
  };
  sessions[sessionIndex].messages.push(newMessage);
  // 更新会话最后更新时间
  sessions[sessionIndex].updateTime = new Date().toISOString();
  // 写入文件
  fs.writeFileSync(sessionFile, JSON.stringify(sessions, null, 2), 'utf-8');
  return sessions[sessionIndex];
}

/**
 * 删除会话
 * @param {String} sessionId - 会话ID
 */
function deleteSession(sessionId) {
  let sessions = getSessionList();
  const sessionIndex = sessions.findIndex(item => item.sessionId === sessionId);
  if (sessionIndex === -1) {
    throw new Error('会话不存在');
  }
  // 删除会话
  sessions = sessions.filter(item => item.sessionId !== sessionId);
  fs.writeFileSync(sessionFile, JSON.stringify(sessions, null, 2), 'utf-8');
  return { success: true, message: '会话删除成功' };
}

module.exports = {
  getSessionList,
  createSession,
  getSessionDetail,
  addSessionMessage,
  deleteSession
};