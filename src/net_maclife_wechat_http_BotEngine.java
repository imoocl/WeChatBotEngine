import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;

import javax.script.*;

import org.apache.commons.lang3.*;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

class net_maclife_wechat_http_BotEngine implements Runnable
{
	// 几种 Bot 链处理方式标志（Bot 链处理方式仅仅在 ${engine.message.dispatch.ThreadMode} 配置为【单线程/共享线程】时才有用）。组合值列表：
	// 0: 本 Bot 没处理，后面的 Bot 也别处理了
	// 1: 本 Bot 已处理，后面的 Bot 别处理了
	// 2: 本 Bot 没处理，但后面的 Bot 请继续处理，别管我……
	// 3: 本 Bot 已处理，但后面的 Bot 也请继续处理
	public static final int BOT_CHAIN_PROCESS_MODE_MASK__PROCESSED = 1;	// 标志位： 消息是否已经处理过。如果此位为 0，则表示未处理过。
	public static final int BOT_CHAIN_PROCESS_MODE_MASK__CONTINUE  = 2;	// 标志位： 消息是否让后面的 Bot 继续处理。如果此位为 0，则表示不让后面的 Bot 继续处理。

	public static final int WECHAT_MSG_TYPE__TEXT                  = 1;
	public static final int WECHAT_MSG_TYPE__IMAGE                 = 3;
	public static final int WECHAT_MSG_TYPE__APP                   = 6;
	public static final int WECHAT_MSG_TYPE__VOICE                 = 34;
	public static final int WECHAT_MSG_TYPE__VERIFY_MSG            = 37;
	public static final int WECHAT_MSG_TYPE__POSSIBLE_FRIEND_MSG   = 40;
	public static final int WECHAT_MSG_TYPE__VCARD                 = 42;
	public static final int WECHAT_MSG_TYPE__VIDEO_CALL            = 43;
	public static final int WECHAT_MSG_TYPE__EMOTION               = 47;
	public static final int WECHAT_MSG_TYPE__GPS_POSITION          = 48;
	public static final int WECHAT_MSG_TYPE__URL                   = 49;
	public static final int WECHAT_MSG_TYPE__VOIP_MSG              = 50;
	public static final int WECHAT_MSG_TYPE__INIT                  = 51;
	public static final int WECHAT_MSG_TYPE__VOIP_NOTIFY           = 52;
	public static final int WECHAT_MSG_TYPE__VOIP_INVITE           = 53;
	public static final int WECHAT_MSG_TYPE__SHORT_VIDEO           = 62;
	public static final int WECHAT_MSG_TYPE__SYSTEM_NOTICE         = 9999;
	public static final int WECHAT_MSG_TYPE__SYSTEM                = 10000;
	public static final int WECHAT_MSG_TYPE__MSG_REVOKED           = 10002;

	Future<?> engineTask = null;
	List<net_maclife_wechat_http_Bot> listBots = new ArrayList<net_maclife_wechat_http_Bot> ();

	boolean loggedIn  = false;

	String sUserID     = null;
	String sSessionID  = null;
	String sSessionKey = null;
	String sPassTicket = null;

	JsonNode jsonMe       = null;
	String sMyAccountHashInThisSession = null;
	String sMyAliasName   = null;
	String sMyNickName    = null;
	String sMyRemarkName  = null;
	JsonNode jsonContacts = null;
	JsonNode jsonRoomContacts = null;

	volatile boolean bStopFlag = false;
	boolean bMultithread = false;

	public net_maclife_wechat_http_BotEngine ()
	{
		bMultithread = StringUtils.equalsIgnoreCase (net_maclife_wechat_http_BotApp.config.getString ("engine.message.dispatch.ThreadMode", ""), "multithread");
	}

	public void Start ()
	{
		LoadBots ();
		engineTask = net_maclife_wechat_http_BotApp.executor.submit (this);
		bStopFlag = false;
	}

	public void Stop ()
	{
		UnloadAllBots ();
		bStopFlag = true;

		if (engineTask!=null && !engineTask.isCancelled ())
		{
			engineTask.cancel (true);
		}
	}

	public void LoadBot (String sBotClassName)
	{
		try
		{
			Class<?> botClass = Class.forName (sBotClassName);
			Object obj = botClass.newInstance ();
			if (obj instanceof net_maclife_wechat_http_Bot)
			{
				net_maclife_wechat_http_Bot newBot = (net_maclife_wechat_http_Bot) obj;
				boolean bAlreadyLoaded = false;
				// 检查有没有该类的实例存在，有的话，则不再重复添加
				for (int i=0; i<listBots.size (); i++)
				{
					net_maclife_wechat_http_Bot bot = listBots.get (i);
					//if (bot.getClass ().isInstance (obj))
						if (botClass.isInstance (bot))
						{
							bAlreadyLoaded = true;
net_maclife_wechat_http_BotApp.logger.warning ("已经加载过 " + bot.GetName() + " 机器人，不重复加载");
							break;
						}
					}

					if (! bAlreadyLoaded)
					{
						newBot.SetEngine (this);
						newBot.Start ();
						listBots.add (newBot);
net_maclife_wechat_http_BotApp.logger.info (newBot.GetName () + " 机器人已创建并加载");
				}
			}
			//
		}
		catch (Exception e)
		{
			e.printStackTrace ();
		}
	}

	public void LoadBots ()
	{
		List<String> listBotClassNames = net_maclife_wechat_http_BotApp.config.getList (String.class, "engine.bots.load.classNames");
		if (listBotClassNames != null)
			for (String sBotClassName : listBotClassNames)
			{
				if (StringUtils.isEmpty (sBotClassName))
					continue;

				LoadBot (sBotClassName);
			}
	}

	public void UnloadBot (net_maclife_wechat_http_Bot bot)
	{
		try
		{
			listBots.remove (bot);
			bot.Stop ();
net_maclife_wechat_http_BotApp.logger.info (bot.GetName () + " 机器人已被卸载");
		}
		catch (Exception e)
		{
			e.printStackTrace ();
		}
	}

	public void UnloadBot (String sBotClassName)
	{
		try
		{
			for (int i=0; i<listBots.size (); i++)
			{
				net_maclife_wechat_http_Bot bot = listBots.get (i);
				if (StringUtils.equalsIgnoreCase (bot.getClass ().getCanonicalName (), sBotClassName))
				{
					UnloadBot (bot);
				}
			}
		}
		catch (Exception e)
		{
			e.printStackTrace ();
		}
	}
	public void UnloadAllBots ()
	{
		try
		{
			for (int i=listBots.size ()-1; i>=0; i--)
			{
				net_maclife_wechat_http_Bot bot = listBots.remove (i);
				UnloadBot (bot);
			}
		}
		catch (Exception e)
		{
			e.printStackTrace ();
		}
	}

	public void ListBots ()
	{
		for (int i=listBots.size ()-1; i>=0; i--)
		{
			net_maclife_wechat_http_Bot bot = listBots.get (i);
			//UnloadBot (bot);
			net_maclife_wechat_http_BotApp.logger.info (bot.GetName ());
		}
	}

	public boolean IsMe (String  sAccountHash)
	{
		return StringUtils.equalsIgnoreCase (sMyAccountHashInThisSession, sAccountHash);
	}

	JsonNode Init () throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		return net_maclife_wechat_http_BotApp.WebWeChatInit (sUserID, sSessionID, sSessionKey, sPassTicket);
	}

	JsonNode StatusNotify () throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		return net_maclife_wechat_http_BotApp.WebWeChatStatusNotify (sUserID, sSessionID, sSessionKey, sPassTicket, sMyAccountHashInThisSession);
	}

	JsonNode GetContacts () throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		return net_maclife_wechat_http_BotApp.WebWeChatGetContacts (sUserID, sSessionID, sSessionKey, sPassTicket);
	}

	JsonNode GetRoomContacts (List<String> listRoomIDs) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		return net_maclife_wechat_http_BotApp.WebWeChatGetRoomContacts (sUserID, sSessionID, sSessionKey, sPassTicket, listRoomIDs);
	}

	JsonNode GetMessages (JsonNode jsonSyncCheckKeys) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException, ScriptException, URISyntaxException
	{
		return net_maclife_wechat_http_BotApp.WebWeChatGetMessages (sUserID, sSessionID, sSessionKey, sPassTicket, jsonSyncCheckKeys);
	}

	public void Logout ()
	{
		try
		{
			net_maclife_wechat_http_BotApp.WebWeChatLogout (sUserID, sSessionID, sSessionKey, sPassTicket);
			loggedIn = false;
			OnLoggedOut ();
		}
		catch (Exception e)
		{
			e.printStackTrace ();
		}
	}

	public void SendTextMessage (String sTo_RoomAccountHash, String sTo_AccountHash, String sTo_NickName, String sMessage) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		if (net_maclife_wechat_http_BotApp.ParseBoolean (net_maclife_wechat_http_BotApp.config.getString ("engine.message.text.appendTimestamp", "yes")))
		{
			sMessage = sMessage + "\n" + new java.sql.Timestamp (System.currentTimeMillis ());
		}
		if (StringUtils.isEmpty (sTo_RoomAccountHash))
		{	// 私信，直接发送
			net_maclife_wechat_http_BotApp.WebWeChatSendTextMessage (sUserID, sSessionID, sSessionKey, sPassTicket, sMyAccountHashInThisSession, sTo_AccountHash, sMessage);
		}
		else
		{	// 聊天室，需要做一下处理： @一下发送人，然后是消息
			net_maclife_wechat_http_BotApp.WebWeChatSendTextMessage (sUserID, sSessionID, sSessionKey, sPassTicket, sMyAccountHashInThisSession, sTo_RoomAccountHash, (StringUtils.isNotEmpty (sTo_NickName) ? "@" + sTo_NickName + "\n" : "") + sMessage);
		}
	}
	public void SendTextMessage (String sTo_AccountHash, String sTo_NickName, String sMessage) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		SendTextMessage (null, sTo_AccountHash, sTo_NickName, sMessage);
	}
	public void SendTextMessage (String sTo_AccountHash, String sMessage) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		SendTextMessage (null, sTo_AccountHash, null, sMessage);
	}

	public void BotSendTextMessage (net_maclife_wechat_http_Bot bot, String sTo_RoomAccountHash, String sTo_AccountHash, String sTo_NickName, String sMessage) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		if (net_maclife_wechat_http_BotApp.ParseBoolean (net_maclife_wechat_http_BotApp.config.getString ("engine.message.text.appendBotName", "yes")))
		{
			sMessage = sMessage + "\n-- " + bot.GetName ();
		}
		SendTextMessage (sTo_RoomAccountHash, sTo_AccountHash, sTo_NickName, sMessage);
	}
	public void BotSendTextMessage (net_maclife_wechat_http_Bot bot, String sTo_AccountHash, String sTo_NickName, String sMessage) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		BotSendTextMessage (bot, null, sTo_AccountHash, sTo_NickName, sMessage);
	}
	public void BotSendTextMessage (net_maclife_wechat_http_Bot bot, String sTo_AccountHash, String sMessage) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		BotSendTextMessage (bot, null, sTo_AccountHash, null, sMessage);
	}

	public List<JsonNode> SearchForContacts (String sAccountHashInThisSession, String sAliasAccount, String sRemarkName, String sNickName)
	{
		return net_maclife_wechat_http_BotApp.SearchForContacts (jsonContacts.get ("MemberList"), sAccountHashInThisSession, sAliasAccount, sRemarkName, sNickName);
	}
	public JsonNode SearchForSingleContact (String sAccountHashInThisSession, String sAliasAccount, String sRemarkName, String sNickName)
	{
		return net_maclife_wechat_http_BotApp.SearchForSingleContact (jsonContacts.get ("MemberList"), sAccountHashInThisSession, sAliasAccount, sRemarkName, sNickName);
	}

	public JsonNode GetRoomByRoomAccountHash (String sRoomAccountHashInThisSession)
	{
		JsonNode jsonRooms = jsonRoomContacts.get ("ContactList");
		JsonNode jsonRoom = null;
		for (int i=0; i<jsonRooms.size (); i++)
		{
			jsonRoom = jsonRooms.get (i);
			if (StringUtils.equalsIgnoreCase (sRoomAccountHashInThisSession, net_maclife_wechat_http_BotApp.GetJSONText (jsonRoom, "UserName")))
				return jsonRoom;
		}

		// 如果找不到聊天室，则尝试重新获取一次
		List<String> listRoomIDs = new ArrayList<String> ();
		listRoomIDs.add (sRoomAccountHashInThisSession);
		try
		{
			JsonNode jsonThisRoomContact = net_maclife_wechat_http_BotApp.WebWeChatGetRoomContacts (sUserID, sSessionID, sSessionKey, sPassTicket, listRoomIDs);
			JsonNode jsonThisRooms = jsonThisRoomContact.get ("ContactList");
			if (jsonThisRooms.size () == 1)
			{
				jsonRoom = jsonThisRooms.get (0);
				((ArrayNode)jsonRooms).add (jsonRoom);
				return jsonRoom;
			}
		}
		catch (KeyManagementException | UnrecoverableKeyException | NoSuchAlgorithmException | KeyStoreException | CertificateException | IOException e)
		{
			e.printStackTrace();
		}
		return null;
	}
	public List<JsonNode> SearchForContactsInRoom (String sRoomAccountHashInThisSession, String sAccountHashInThisSession, String sAliasAccount, String sRemarkName, String sNickName)
	{
		JsonNode jsonRoom = GetRoomByRoomAccountHash (sRoomAccountHashInThisSession);
		return net_maclife_wechat_http_BotApp.SearchForContacts (jsonRoom.get ("MemberList"), sAccountHashInThisSession, sAliasAccount, sRemarkName, sNickName);
	}
	public JsonNode SearchForSingleContactInRoom (String sRoomAccountHashInThisSession, String sAccountHashInThisSession, String sAliasAccount, String sRemarkName, String sNickName)
	{
		JsonNode jsonRoom = GetRoomByRoomAccountHash (sRoomAccountHashInThisSession);
		return net_maclife_wechat_http_BotApp.SearchForSingleContact (jsonRoom.get ("MemberList"), sAccountHashInThisSession, sAliasAccount, sRemarkName, sNickName);
	}

	/**
	 * Bot 引擎线程： 不断循环尝试登录，直到登录成功。如果登录成功后被踢下线，依旧不断循环尝试登录…… 登录成功后，不断同步消息，直到被踢下线（同上，依旧不断循环尝试登录）
	 */
	@Override
	public void run ()
	{
		try
		{
			String sLoginID = null;
			Map<String, Object> mapWaitLoginResult = null;
			Object o = null;
		_outer_loop:
			while (! Thread.interrupted () && !bStopFlag)
			{
				try
				{
					// 1. 获得登录 ID
					sLoginID = net_maclife_wechat_http_BotApp.GetNewLoginID ();

					// 2. 根据登录 ID，获得登录地址的二维码图片 （暂时只能扫描图片，不能根据登录地址自动登录 -- 暂时无法截获手机微信 扫描二维码以及确认登录时 时带的参数，所以无法模拟自动登录）
					net_maclife_wechat_http_BotApp.GetLoginQRCodeImageFile (sLoginID);

					// 3. 等待二维码扫描（）、以及确认登录
					do
					{
						o = net_maclife_wechat_http_BotApp.等待二维码被扫描以便登录 (sLoginID);
						if (o instanceof Integer)
						{
							int n = (Integer) o;
							if (n == 400)	// Bad Request / 二维码已失效
							{
								continue _outer_loop;
							}
							else	// 大概只有 200 才能出来：当是 200 时，但访问登录页面失败时，可能会跑到此处
							{
								//
							}
						}
					} while (! (o instanceof Map<?, ?>) && !bStopFlag);
					mapWaitLoginResult = (Map<String, Object>) o;
					sUserID     = (String) mapWaitLoginResult.get ("UserID");
					sSessionID  = (String) mapWaitLoginResult.get ("SessionID");
					sSessionKey = (String) mapWaitLoginResult.get ("SessionKey");
					sPassTicket = (String) mapWaitLoginResult.get ("PassTicket");

					// 4. 确认登录后，初始化 Web 微信，返回初始信息
					JsonNode jsonInit = Init ();
					jsonMe = jsonInit.get ("User");
					sMyAccountHashInThisSession = net_maclife_wechat_http_BotApp.GetJSONText (jsonMe, "UserName");
					sMyAliasName = net_maclife_wechat_http_BotApp.GetJSONText (jsonMe, "Alias");
					sMyNickName = net_maclife_wechat_http_BotApp.GetJSONText (jsonMe, "NickName");
					JsonNode jsonSyncCheckKeys = jsonInit.get ("SyncKey");

					JsonNode jsonStatusNotify = StatusNotify ();

					// 5. 获取联系人
					jsonContacts = GetContacts ();
					List<String> listRoomIDs = net_maclife_wechat_http_BotApp.GetRoomIDsFromContacts (jsonContacts);
					jsonRoomContacts = GetRoomContacts (listRoomIDs);	// 补全各个群的联系人列表

					// 触发“已登录”事件
					OnLoggedIn ();

					JsonNode jsonMessage = null;
					try
					{
						while (! Thread.interrupted () && !bStopFlag)
						{
							jsonMessage = GetMessages (jsonSyncCheckKeys);
							if (jsonMessage == null)
							{
								TimeUnit.SECONDS.sleep (2);
								continue;
							}

							JsonNode jsonBaseResponse = jsonMessage.get ("BaseResponse");
							int nRet = net_maclife_wechat_http_BotApp.GetJSONInt (jsonBaseResponse, "Ret");
							String sErrMsg = net_maclife_wechat_http_BotApp.GetJSONText (jsonBaseResponse, "ErrMsg");
							if (nRet != 0)
							{
								System.err.print ("同步消息失败: 代码=" + nRet);
								if (StringUtils.isNotEmpty (sErrMsg))
								{
									System.err.print ("，消息=" + sErrMsg);
								}
								System.err.println ();
								TimeUnit.SECONDS.sleep (2);
								continue;
							}

							// 处理“接收”到的（实际是同步获取而来）消息
							jsonSyncCheckKeys = jsonMessage.get ("SyncCheckKey");	// 新的 SyncCheckKeys

							// 处理（实际上，应该交给 Bot 们处理）
							OnMessageReceived (jsonMessage);
						}
					}
					catch (IllegalStateException e)
					{
						continue _outer_loop;
					}
				}
				catch (Exception e)
				{
					e.printStackTrace ();
					// 因为在循环内，出现异常，先暂停一会，免得不断重复
					TimeUnit.SECONDS.sleep (2);
				}
			}
		}
		catch (Exception e)
		{
			e.printStackTrace ();
		}

net_maclife_wechat_http_BotApp.logger.warning ("bot 线程退出");
		OnShutdown ();
	}

	void OnLoggedIn ()
	{
		loggedIn = true;
		DispatchEvent ("OnLoggedIn", null, null, null, null, null, null, null);
	}

	void OnLoggedOut ()
	{
		DispatchEvent ("OnLoggedOut", null, null, null, null, null, null, null);
	}

	void OnShutdown ()
	{
		DispatchEvent ("OnShutdown", null, null, null, null, null, null, null);
	}

	void OnMessageReceived (JsonNode jsonMessage) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException, URISyntaxException
	{
		int i = 0;

		int nAddMsgCount = net_maclife_wechat_http_BotApp.GetJSONInt (jsonMessage, "AddMsgCount", 0);
		JsonNode jsonAddMsgList = jsonMessage.get ("AddMsgList");
		for (i=0; i<nAddMsgCount; i++)
		{
			JsonNode jsonNode = jsonAddMsgList.get (i);
			String sMsgID = net_maclife_wechat_http_BotApp.GetJSONText (jsonNode, "MsgId");
			int nMsgType = net_maclife_wechat_http_BotApp.GetJSONInt (jsonNode, "MsgType");
			String sContent = net_maclife_wechat_http_BotApp.GetJSONText (jsonNode, "Content");
			sContent = StringUtils.replaceEach (sContent, new String[]{"<br/>", "&lt;", "&gt;"}, new String[]{"\n", "<", ">"});
			sContent = StringEscapeUtils.unescapeHtml4 (sContent);
			String sRoom = null;
			String sRoomNickName = null;
			String sFrom = net_maclife_wechat_http_BotApp.GetJSONText (jsonNode, "FromUserName");
			String sFromNickName = null;
			boolean isFromPublicAccount = false;
			String sTo = net_maclife_wechat_http_BotApp.GetJSONText (jsonNode, "ToUserName");
			String sToNickName = null;
			boolean isFromRoomOrChannel = net_maclife_wechat_http_BotApp.IsRoomAccount (sFrom);
			boolean isToRoomOrChannel = net_maclife_wechat_http_BotApp.IsRoomAccount (sTo);
			if (isFromRoomOrChannel)
			{	// 如果是发自聊天室，则从聊天室的成员列表中获取真正的发送人（可能不在自己的联系人内，只能从聊天室成员列表中获取）
				sRoom = sFrom;
				JsonNode jsonRoom = GetRoomByRoomAccountHash (sRoom);
				sRoomNickName = net_maclife_wechat_http_BotApp.GetJSONText (jsonRoom, "NickName");

				// 找出发送人的 UserID
				String[] arrayContents = sContent.split (":\n", 2);
				sFrom = arrayContents[0];
				JsonNode jsonFrom = SearchForSingleContactInRoom (sRoom, sFrom, null, null, null);
				String sFrom_DisplayName = net_maclife_wechat_http_BotApp.GetJSONText (jsonFrom, "DisplayName");
				if (StringUtils.isNotEmpty(sFrom_DisplayName))
					sFromNickName = sFrom_DisplayName;
				else
					sFromNickName = net_maclife_wechat_http_BotApp.GetJSONText (jsonFrom, "NickName");

				sContent = arrayContents[1];
			}
			else
			{	//
				JsonNode jsonFrom = SearchForSingleContact (sFrom, null, null, null);
				String sFrom_DisplayName = net_maclife_wechat_http_BotApp.GetJSONText (jsonFrom, "DisplayName");
				if (StringUtils.isNotEmpty(sFrom_DisplayName))
					sFromNickName = sFrom_DisplayName;
				else
					sFromNickName = net_maclife_wechat_http_BotApp.GetJSONText (jsonFrom, "NickName");
				isFromPublicAccount = net_maclife_wechat_http_BotApp.IsPublicAccount (net_maclife_wechat_http_BotApp.GetJSONInt (jsonFrom, "VerifyFlag"));
			}

			if (isToRoomOrChannel)
			{	// 如果是发送到聊天室，通常、几乎就是自己的帐号在其他设备上发的
				// 首先，先找出自己在该聊天室/群中的昵称
				JsonNode jsonFrom = SearchForSingleContactInRoom (sTo, sFrom, null, null, null);
				String sFrom_DisplayName = net_maclife_wechat_http_BotApp.GetJSONText (jsonFrom, "DisplayName");
				if (StringUtils.isNotEmpty(sFrom_DisplayName))
					sFromNickName = sFrom_DisplayName;
				else
					sFromNickName = net_maclife_wechat_http_BotApp.GetJSONText (jsonFrom, "NickName");

				JsonNode jsonRoom = GetRoomByRoomAccountHash (sTo);
				sToNickName = net_maclife_wechat_http_BotApp.GetJSONText (jsonRoom, "NickName");
				if (IsMe (sFrom))
				{	// 能够收到自己的帐号从其他设备发的消息：发件人是自己、收件人人是聊天室
					sRoom = sTo;
					//sTo = sFrom;	// 这么做，在自己的 BotEngine 内应该没啥问题吧
				}
			}
			else
			{
				JsonNode jsonTo = SearchForSingleContact (sTo, null, null, null);
				sToNickName = net_maclife_wechat_http_BotApp.GetJSONText (jsonTo, "NickName");
			}

			if (net_maclife_wechat_http_BotApp.ParseBoolean (net_maclife_wechat_http_BotApp.config.getString ("engine.message.ignoreMySelf", "no"), false))
			{
				if (IsMe (sFrom))	// 自己发送的消息，不再处理
					continue;
			}

net_maclife_wechat_http_BotApp.logger.info ("收到来自 " + sFromNickName + " 发给 " + sToNickName + " 的消息 (聊天室: " + sRoomNickName + ")：" + sContent);

			File fMedia = null;
			switch (nMsgType)
			{
			case WECHAT_MSG_TYPE__TEXT:
				OnTextMessageReceived (sRoom, sRoomNickName, sFrom, sFromNickName, sTo, sToNickName, sContent);
				break;
			case WECHAT_MSG_TYPE__IMAGE:
				fMedia = net_maclife_wechat_http_BotApp.WebWeChatGetImage (sSessionKey, sMsgID);
				OnImageMessageReceived (sRoom, sRoomNickName, sFrom, sFromNickName, sTo, sToNickName, fMedia);
				break;
			case WECHAT_MSG_TYPE__APP:
				break;
			case WECHAT_MSG_TYPE__VOICE:
				fMedia = net_maclife_wechat_http_BotApp.WebWeChatGetVoice (sSessionKey, sMsgID);
				OnVoiceMessageReceived (sRoom, sRoomNickName, sFrom, sFromNickName, sTo, sToNickName, fMedia);
				break;
			case WECHAT_MSG_TYPE__VERIFY_MSG:
				break;
			case WECHAT_MSG_TYPE__POSSIBLE_FRIEND_MSG:
				break;
			case WECHAT_MSG_TYPE__VCARD:
				break;
			case WECHAT_MSG_TYPE__VIDEO_CALL:
				break;
			case WECHAT_MSG_TYPE__EMOTION:
				fMedia = net_maclife_wechat_http_BotApp.WebWeChatGetImage (sSessionKey, sMsgID);
				OnEmotionMessageReceived (sRoom, sRoomNickName, sFrom, sFromNickName, sTo, sToNickName, fMedia);
				break;
			case WECHAT_MSG_TYPE__GPS_POSITION:
				break;
			case WECHAT_MSG_TYPE__URL:
				break;
			case WECHAT_MSG_TYPE__VOIP_MSG:
				break;
			case WECHAT_MSG_TYPE__INIT:
				break;
			case WECHAT_MSG_TYPE__VOIP_NOTIFY:
				break;
			case WECHAT_MSG_TYPE__VOIP_INVITE:
				break;
			case WECHAT_MSG_TYPE__SHORT_VIDEO:
				fMedia = net_maclife_wechat_http_BotApp.WebWeChatGetVideo (sSessionKey, sMsgID);
				OnVideoMessageReceived (sRoom, sRoomNickName, sFrom, sFromNickName, sTo, sToNickName, fMedia);
				break;
			case WECHAT_MSG_TYPE__SYSTEM_NOTICE:
				break;
			case WECHAT_MSG_TYPE__SYSTEM:
				break;
			case WECHAT_MSG_TYPE__MSG_REVOKED:
				break;
			default:
				break;
			}
		}

		int nModContactCount = net_maclife_wechat_http_BotApp.GetJSONInt (jsonMessage, "ModContactCount", 0);
		JsonNode jsonModContactList = jsonMessage.get ("ModContactList");
		for (i=0; i<nModContactCount; i++)
		{
			JsonNode jsonNode = jsonAddMsgList.get (i);
		}

		int nDelContactCount = net_maclife_wechat_http_BotApp.GetJSONInt (jsonMessage, "DelContactCount", 0);
		JsonNode jsonDelContactList = jsonMessage.get ("DelContactList");
		for (i=0; i<nDelContactCount; i++)
		{
			JsonNode jsonNode = jsonAddMsgList.get (i);
		}

		int nModChatRoomMemerCount = net_maclife_wechat_http_BotApp.GetJSONInt (jsonMessage, "ModChatRoomMemberCount", 0);
		JsonNode jsonModChatRoomMemerList = jsonMessage.get ("ModChatRoomMemberList");
		for (i=0; i<nModChatRoomMemerCount; i++)
		{
			JsonNode jsonNode = jsonAddMsgList.get (i);
		}
	}

	void OnTextMessageReceived (final String sFrom_RoomAccountHash, final String sFrom_RoomNickName, final String sFrom_AccountHash, final String sFrom_NickName, final String sTo_AccountHash, final String sTo_NickName, final String sMessage) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		DispatchEvent ("OnTextMessage", sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, sMessage);
	}

	void OnEmotionMessageReceived (final String sFrom_RoomAccountHash, final String sFrom_RoomNickName, final String sFrom_AccountHash, final String sFrom_NickName, final String sTo_AccountHash, final String sTo_NickName, final File fMedia) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		DispatchEvent ("OnEmotionMessage", sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, fMedia);
	}

	void OnImageMessageReceived (final String sFrom_RoomAccountHash, final String sFrom_RoomNickName, final String sFrom_AccountHash, final String sFrom_NickName, final String sTo_AccountHash, final String sTo_NickName, final File fMedia) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		DispatchEvent ("OnImageMessage", sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, fMedia);
	}

	void OnVoiceMessageReceived (final String sFrom_RoomAccountHash, final String sFrom_RoomNickName, final String sFrom_AccountHash, final String sFrom_NickName, final String sTo_AccountHash, final String sTo_NickName, final File fMedia) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		DispatchEvent ("OnVoiceMessage", sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, fMedia);
	}

	void OnVideoMessageReceived (final String sFrom_RoomAccountHash, final String sFrom_RoomNickName, final String sFrom_AccountHash, final String sFrom_NickName, final String sTo_AccountHash, final String sTo_NickName, final File fMedia) throws KeyManagementException, UnrecoverableKeyException, JsonProcessingException, NoSuchAlgorithmException, KeyStoreException, CertificateException, IOException
	{
		DispatchEvent ("OnVideoMessage", sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, fMedia);
	}

	void DispatchEvent (final String sType, final String sFrom_RoomAccountHash, final String sFrom_RoomNickName, final String sFrom_AccountHash, final String sFrom_NickName, final String sTo_AccountHash, final String sTo_NickName, final Object data)
	{
		int rc = 0;
		for (final net_maclife_wechat_http_Bot bot : listBots)
		{
			if (! bMultithread)
			{	// 单线程或共享 Engine 线程时，才会有 Bot 链的处理机制。
				rc = DoDispatch (bot, sType, sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, data);
				if ((rc & BOT_CHAIN_PROCESS_MODE_MASK__CONTINUE) != BOT_CHAIN_PROCESS_MODE_MASK__CONTINUE)
					break;
			}
			else
			{	// 多线程时，不采用 Bot 链的处理机制 -- 全部共同执行。
				net_maclife_wechat_http_BotApp.executor.submit
				(
					new Runnable ()
					{
						@Override
						public void run ()
						{
							DoDispatch (bot, sType, sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, data);
						}
					}
				);
			}
		}
	}

	int DoDispatch (final net_maclife_wechat_http_Bot bot, final String sType, final String sFrom_RoomAccountHash, final String sFrom_RoomNickName, final String sFrom_AccountHash, final String sFrom_NickName, final String sTo_AccountHash, final String sTo_NickName, final Object data)
	{
		switch (StringUtils.lowerCase (sType))
		{
		case "onloggedin":
			return bot.OnLoggedIn ();
			//break;
		case "onloggedout":
			return bot.OnLoggedOut ();
			//break;
		case "onshutdown":
			return bot.OnShutdown ();
			//break;
		case "onmessage":
			return bot.OnMessageReceived (sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, (JsonNode)data);
			//break;
		case "ontextmessage":
			return bot.OnTextMessageReceived (sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, (String)data);
			//break;
		case "onimagemessage":
			return bot.OnImageMessageReceived (sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, (File)data);
			//break;
		case "onvoicemessage":
			return bot.OnVoiceMessageReceived (sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, (File)data);
			//break;
		case "onvideomessage":
			return bot.OnVideoMessageReceived (sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, (File)data);
			//break;
		case "onemotionmessage":
			return bot.OnEmotionMessageReceived (sFrom_RoomAccountHash, sFrom_RoomNickName, sFrom_AccountHash, sFrom_NickName, sTo_AccountHash, sTo_NickName, (File)data);
			//break;
		default:
			break;
		}
		return BOT_CHAIN_PROCESS_MODE_MASK__CONTINUE;
	}
}